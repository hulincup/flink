/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.planner.plan.nodes.physical.stream

import org.apache.flink.annotation.VisibleForTesting
import org.apache.flink.api.dag.Transformation
import org.apache.flink.cep.EventComparator
import org.apache.flink.cep.nfa.aftermatch.AfterMatchSkipStrategy
import org.apache.flink.cep.nfa.compiler.NFACompiler
import org.apache.flink.cep.operator.CepOperator
import org.apache.flink.cep.pattern.Pattern
import org.apache.flink.cep.pattern.Quantifier.QuantifierProperty
import org.apache.flink.cep.pattern.conditions.BooleanConditions
import org.apache.flink.streaming.api.operators.ProcessOperator
import org.apache.flink.streaming.api.transformations.OneInputTransformation
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.table.api.{TableConfig, TableException, ValidationException}
import org.apache.flink.table.data.RowData
import org.apache.flink.table.planner.calcite.FlinkTypeFactory
import org.apache.flink.table.planner.codegen.sort.ComparatorCodeGenerator
import org.apache.flink.table.planner.codegen.{CodeGeneratorContext, MatchCodeGenerator}
import org.apache.flink.table.planner.delegation.StreamPlanner
import org.apache.flink.table.planner.plan.logical.MatchRecognize
import org.apache.flink.table.planner.plan.nodes.exec.StreamExecNode
import org.apache.flink.table.planner.plan.utils.PythonUtil.containsPythonCall
import org.apache.flink.table.planner.plan.utils.RelExplainUtil._
import org.apache.flink.table.planner.plan.utils.{KeySelectorUtil, RexDefaultVisitor, SortUtil}
import org.apache.flink.table.runtime.operators.`match`.{RowDataEventComparator, RowtimeProcessFunction}
import org.apache.flink.table.runtime.typeutils.InternalTypeInfo
import org.apache.flink.table.types.logical.RowType
import org.apache.flink.util.MathUtils

import org.apache.calcite.plan.{RelOptCluster, RelTraitSet}
import org.apache.calcite.rel.RelFieldCollation.Direction
import org.apache.calcite.rel._
import org.apache.calcite.rel.`type`.RelDataType
import org.apache.calcite.rex._
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.SqlMatchRecognize.AfterOption
import org.apache.calcite.sql.`type`.SqlTypeFamily
import org.apache.calcite.sql.fun.SqlStdOperatorTable._
import org.apache.calcite.tools.RelBuilder

import _root_.java.lang.{Boolean => JBoolean, Long => JLong}
import _root_.java.util

import _root_.scala.collection.JavaConversions._

/**
  * Flink RelNode which matches along with LogicalMatch.
  */
class StreamExecMatch(
    cluster: RelOptCluster,
    traitSet: RelTraitSet,
    inputNode: RelNode,
    logicalMatch: MatchRecognize,
    outputRowType: RelDataType)
  extends SingleRel(cluster, traitSet, inputNode)
  with StreamPhysicalRel
  with StreamExecNode[RowData] {

  if (logicalMatch.measures.values().exists(containsPythonCall(_)) ||
    logicalMatch.patternDefinitions.values().exists(containsPythonCall(_))) {
    throw new TableException("Python Function can not be used in MATCH_RECOGNIZE for now.")
  }

  override def requireWatermark: Boolean = {
    val rowtimeFields = getInput.getRowType.getFieldList
      .filter(f => FlinkTypeFactory.isRowtimeIndicatorType(f.getType))
    rowtimeFields.nonEmpty
  }

  override def deriveRowType(): RelDataType = outputRowType

  override def copy(traitSet: RelTraitSet, inputs: util.List[RelNode]): RelNode = {
    new StreamExecMatch(
      cluster,
      traitSet,
      inputs.get(0),
      logicalMatch,
      outputRowType)
  }

  override def explainTerms(pw: RelWriter): RelWriter = {
    val inputRowType = getInput.getRowType
    val fieldNames = inputRowType.getFieldNames.toList
    super.explainTerms(pw)
      .itemIf("partitionBy",
        fieldToString(logicalMatch.partitionKeys.toArray, inputRowType),
        !logicalMatch.partitionKeys.isEmpty)
      .itemIf("orderBy",
        collationToString(logicalMatch.orderKeys, inputRowType),
        !logicalMatch.orderKeys.getFieldCollations.isEmpty)
      .itemIf("measures",
        measuresDefineToString(logicalMatch.measures, fieldNames, getExpressionString),
        !logicalMatch.measures.isEmpty)
      .item("rowsPerMatch", rowsPerMatchToString(logicalMatch.allRows))
      .item("after", afterMatchToString(logicalMatch.after, fieldNames))
      .item("pattern", logicalMatch.pattern.toString)
      .itemIf("subset",
        subsetToString(logicalMatch.subsets),
        !logicalMatch.subsets.isEmpty)
      .item("define", logicalMatch.patternDefinitions)
  }

  private def translateTimeBound(interval: RexNode): Time = {
    interval match {
      case x: RexLiteral if x.getTypeName.getFamily == SqlTypeFamily.INTERVAL_DAY_TIME =>
        Time.milliseconds(x.getValueAs(classOf[JLong]))
      case _ =>
        throw new TableException("Only constant intervals with millisecond resolution " +
          "are supported as time constraints of patterns.")
    }
  }

  @VisibleForTesting
  private[flink] def translatePattern(
      config: TableConfig,
      relBuilder: RelBuilder,
      inputRowType: RowType): (Pattern[RowData, RowData], Seq[String]) = {
    val patternVisitor = new PatternVisitor(config, relBuilder, inputRowType, logicalMatch)
    val cepPattern = if (logicalMatch.interval != null) {
      val interval = translateTimeBound(logicalMatch.interval)
      logicalMatch.pattern.accept(patternVisitor).within(interval)
    } else {
      logicalMatch.pattern.accept(patternVisitor)
    }
    (cepPattern, patternVisitor.names.toSeq)
  }

  //~ ExecNode methods -----------------------------------------------------------

  override protected def translateToPlanInternal(
      planner: StreamPlanner): Transformation[RowData] = {

    val config = planner.getTableConfig
    val relBuilder = planner.getRelBuilder
    val returnType = FlinkTypeFactory.toLogicalRowType(getRowType)
    val inputRowType = FlinkTypeFactory.toLogicalRowType(getInput.getRowType)

    val inputTransform = getInputNodes.get(0).translateToPlan(planner)
      .asInstanceOf[Transformation[RowData]]

    val (timestampedInput, comparator) = translateOrder(
      config, inputTransform, logicalMatch.orderKeys)

    val (cepPattern, patternNames) = translatePattern(
      config,
      relBuilder,
      inputRowType)

    //TODO remove this once it is supported in CEP library
    if (NFACompiler.canProduceEmptyMatches(cepPattern)) {
      throw new TableException(
        "Patterns that can produce empty matches are not supported. There must be at least one " +
          "non-optional state.")
    }

    //TODO remove this once it is supported in CEP library
    if (cepPattern.getQuantifier.hasProperty(QuantifierProperty.GREEDY)) {
      throw new TableException(
        "Greedy quantifiers are not allowed as the last element of a Pattern yet. Finish your " +
          "pattern with either a simple variable or reluctant quantifier.")
    }

    if (logicalMatch.allRows) {
      throw new TableException("All rows per match mode is not supported yet.")
    } else {
      val partitionKeys = logicalMatch.partitionKeys
      val timeOrderField = SortUtil.getFirstSortField(logicalMatch.orderKeys, getInput.getRowType)
      val isProctime = FlinkTypeFactory.isProctimeIndicatorType(timeOrderField.getType)
      val inputTypeInfo = inputTransform.getOutputType.asInstanceOf[InternalTypeInfo[RowData]]
      val inputSerializer = inputTypeInfo.createSerializer(planner.getExecEnv.getConfig)
      val nfaFactory = NFACompiler.compileFactory(cepPattern, false)
      val generator = new MatchCodeGenerator(
        CodeGeneratorContext(config),
        relBuilder,
        false,
        patternNames)
      generator.bindInput(inputRowType)
      val patternProcessFunction = generator.generateOneRowPerMatchExpression(
        returnType,
        partitionKeys,
        logicalMatch.measures)
      val operator = new CepOperator[RowData, RowData, RowData](
        inputSerializer,
        isProctime,
        nfaFactory,
        comparator,
        cepPattern.getAfterMatchSkipStrategy,
        patternProcessFunction,
        null
      )
      val outputRowTypeInfo = InternalTypeInfo.of(FlinkTypeFactory.toLogicalRowType(getRowType))
      val transformation = new OneInputTransformation[RowData, RowData](
        timestampedInput,
        getRelDetailedDescription,
        operator,
        outputRowTypeInfo,
        timestampedInput.getParallelism
      )
      if (inputsContainSingleton()) {
        transformation.setParallelism(1)
        transformation.setMaxParallelism(1)
      }
      setKeySelector(transformation, inputTypeInfo)
      transformation
    }
  }

  private def translateOrder(
      config: TableConfig,
      inputTransform: Transformation[RowData],
      orderKeys: RelCollation): (Transformation[RowData], EventComparator[RowData]) = {

    if (orderKeys.getFieldCollations.size() == 0) {
      throw new ValidationException("You must specify either rowtime or proctime for order by.")
    }

    // need to identify time between others order fields. Time needs to be first sort element
    val timeOrderField = SortUtil.getFirstSortField(orderKeys, getInput.getRowType)

    if (!FlinkTypeFactory.isTimeIndicatorType(timeOrderField.getType)) {
      throw new ValidationException(
        "You must specify either rowtime or proctime for order by as the first one.")
    }

    // time ordering needs to be ascending
    if (SortUtil.getFirstSortDirection(orderKeys) != Direction.ASCENDING) {
      throw new ValidationException(
        "Primary sort order of a streaming table must be ascending on time.")
    }

    val eventComparator = if (orderKeys.getFieldCollations.size() > 1) {
      val inputType = FlinkTypeFactory.toLogicalRowType(getInput.getRowType)
      val (keys, orders, nullsIsLast) = SortUtil.getKeysAndOrders(orderKeys.getFieldCollations)
      val keyTypes = keys.map(inputType.getTypeAt)
      val rowComparator = ComparatorCodeGenerator.gen(
        config,
        "RowDataComparator",
        keys,
        keyTypes,
        orders,
        nullsIsLast)
      new RowDataEventComparator(rowComparator)
    } else {
      null
    }

    val timestampedInputTransform =
      if (FlinkTypeFactory.isRowtimeIndicatorType(timeOrderField.getType)) {
        // copy the rowtime field into the StreamRecord timestamp field
        val timeIdx = timeOrderField.getIndex
        val inputTypeInfo = inputTransform.getOutputType
        val precision = timeOrderField.getType.getPrecision
        val transformation = new OneInputTransformation(
          inputTransform,
          s"rowtime field: ($timeOrderField)",
          new ProcessOperator(new RowtimeProcessFunction(timeIdx, inputTypeInfo, precision)),
          inputTypeInfo,
          inputTransform.getParallelism)
        if (inputsContainSingleton()) {
          transformation.setParallelism(1)
          transformation.setMaxParallelism(1)
        }
        transformation
      } else {
        inputTransform
      }

    (timestampedInputTransform, eventComparator)
  }

  private def setKeySelector(
      transform: OneInputTransformation[RowData, _],
      inputTypeInfo: InternalTypeInfo[RowData]): Unit = {
    val selector = KeySelectorUtil.getRowDataSelector(
      logicalMatch.partitionKeys.toArray,
      inputTypeInfo)
    transform.setStateKeySelector(selector)
    transform.setStateKeyType(selector.getProducedType)
  }
}

private class PatternVisitor(
    config: TableConfig,
    relBuilder: RelBuilder,
    inputRowType: RowType,
    logicalMatch: MatchRecognize)
  extends RexDefaultVisitor[Pattern[RowData, RowData]] {

  private var pattern: Pattern[RowData, RowData] = _
  val names = new collection.mutable.LinkedHashSet[String]()

  override def visitLiteral(literal: RexLiteral): Pattern[RowData, RowData] = {
    val patternName = literal.getValueAs(classOf[String])
    pattern = translateSingleVariable(Option.apply(pattern), patternName)

    val patternDefinition = logicalMatch.patternDefinitions.get(patternName)
    if (patternDefinition != null) {
      val generator = new MatchCodeGenerator(
        CodeGeneratorContext(config),
        relBuilder,
        false,
        names.toSeq,
        Some(patternName))
      generator.bindInput(inputRowType)
      val condition = generator.generateIterativeCondition(patternDefinition)

      pattern.where(condition)
    } else {
      pattern.where(BooleanConditions.trueFunction())
    }
  }

  override def visitCall(call: RexCall): Pattern[RowData, RowData] = {
    call.getOperator match {
      case PATTERN_CONCAT =>
        val left = call.operands.get(0)
        val right = call.operands.get(1)

        pattern = left.accept(this)
        pattern = right.accept(this)
        pattern

      case PATTERN_QUANTIFIER =>
        val name = call.operands.get(0) match {
          case c: RexLiteral => c
          case x => throw new TableException(s"Expression not supported: $x Group patterns are " +
            s"not supported yet.")
        }
        pattern = name.accept(this)
        val startNum = MathUtils.checkedDownCast(call.operands.get(1).asInstanceOf[RexLiteral]
          .getValueAs(classOf[JLong]))
        val endNum = MathUtils.checkedDownCast(call.operands.get(2).asInstanceOf[RexLiteral]
          .getValueAs(classOf[JLong]))
        val isGreedy = !call.operands.get(3).asInstanceOf[RexLiteral]
          .getValueAs(classOf[JBoolean])

        applyQuantifier(pattern, startNum, endNum, isGreedy)

      case PATTERN_ALTER =>
        throw new TableException(
          s"Expression not supported: $call. Currently, CEP doesn't support branching patterns.")

      case PATTERN_PERMUTE =>
        throw new TableException(
          s"Expression not supported: $call. Currently, CEP doesn't support PERMUTE patterns.")

      case PATTERN_EXCLUDE =>
        throw new TableException(
          s"Expression not supported: $call. Currently, CEP doesn't support '{-' '-}' patterns.")
    }
  }

  override def visitNode(rexNode: RexNode): Pattern[RowData, RowData] = throw new TableException(
    s"Unsupported expression within Pattern: [$rexNode]")

  private def translateSkipStrategy = {
    val getPatternTarget = () => logicalMatch.after.asInstanceOf[RexCall].getOperands.get(0)
      .asInstanceOf[RexLiteral].getValueAs(classOf[String])

    logicalMatch.after.getKind match {
      case SqlKind.LITERAL =>
        logicalMatch.after.asInstanceOf[RexLiteral].getValueAs(classOf[AfterOption]) match {
          case AfterOption.SKIP_PAST_LAST_ROW => AfterMatchSkipStrategy.skipPastLastEvent()
          case AfterOption.SKIP_TO_NEXT_ROW => AfterMatchSkipStrategy.skipToNext()
        }
      case SqlKind.SKIP_TO_FIRST =>
        AfterMatchSkipStrategy.skipToFirst(getPatternTarget()).throwExceptionOnMiss()
      case SqlKind.SKIP_TO_LAST =>
        AfterMatchSkipStrategy.skipToLast(getPatternTarget()).throwExceptionOnMiss()
      case _ => throw new IllegalStateException(s"Corrupted query tree. Unexpected " +
        s"${logicalMatch.after} for after match strategy.")
    }
  }

  private def translateSingleVariable(
      previousPattern: Option[Pattern[RowData, RowData]],
      patternName: String)
    : Pattern[RowData, RowData] = {
    if (names.contains(patternName)) {
      throw new TableException("Pattern variables must be unique. That might change in the future.")
    } else {
      names.add(patternName)
    }

    previousPattern match {
      case Some(p) => p.next(patternName)
      case None =>
        Pattern.begin(patternName, translateSkipStrategy)
    }
  }

  private def applyQuantifier(
      pattern: Pattern[RowData, RowData],
      startNum: Int,
      endNum: Int,
      greedy: Boolean)
    : Pattern[RowData, RowData] = {
    val isOptional = startNum == 0 && endNum == 1

    val newPattern = if (startNum == 0 && endNum == -1) { // zero or more
      pattern.oneOrMore().optional().consecutive()
    } else if (startNum == 1 && endNum == -1) { // one or more
      pattern.oneOrMore().consecutive()
    } else if (isOptional) { // optional
      pattern.optional()
    } else if (endNum != -1) { // times
      pattern.times(startNum, endNum).consecutive()
    } else { // times or more
      pattern.timesOrMore(startNum).consecutive()
    }

    if (greedy && (isOptional || startNum == endNum)) {
      newPattern
    } else if (greedy) {
      newPattern.greedy()
    } else if (isOptional) {
      throw new TableException("Reluctant optional variables are not supported yet.")
    } else {
      newPattern
    }
  }
}
