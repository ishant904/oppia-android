package org.oppia.android.util.math

import org.oppia.android.app.model.MathBinaryOperation
import org.oppia.android.app.model.MathBinaryOperation.Operator.ADD
import org.oppia.android.app.model.MathBinaryOperation.Operator.DIVIDE
import org.oppia.android.app.model.MathBinaryOperation.Operator.EXPONENTIATE
import org.oppia.android.app.model.MathBinaryOperation.Operator.MULTIPLY
import org.oppia.android.app.model.MathBinaryOperation.Operator.SUBTRACT
import org.oppia.android.app.model.MathEquation
import org.oppia.android.app.model.MathExpression
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.BINARY_OPERATION
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.CONSTANT
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.EXPRESSIONTYPE_NOT_SET
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.FUNCTION_CALL
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.GROUP
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.UNARY_OPERATION
import org.oppia.android.app.model.MathExpression.ExpressionTypeCase.VARIABLE
import org.oppia.android.app.model.MathFunctionCall
import org.oppia.android.app.model.MathUnaryOperation
import org.oppia.android.app.model.MathUnaryOperation.Operator.NEGATE
import org.oppia.android.app.model.MathUnaryOperation.Operator.POSITIVE
import org.oppia.android.app.model.Real
import org.oppia.android.util.math.MathExpressionParser.ParseContext.AlgebraicExpressionContext
import org.oppia.android.util.math.MathExpressionParser.ParseContext.NumericExpressionContext
import org.oppia.android.util.math.MathParsingError.DisabledVariablesInUseError
import org.oppia.android.util.math.MathParsingError.EquationHasWrongNumberOfEqualsError
import org.oppia.android.util.math.MathParsingError.EquationMissingLhsOrRhsError
import org.oppia.android.util.math.MathParsingError.ExponentIsVariableExpressionError
import org.oppia.android.util.math.MathParsingError.ExponentTooLargeError
import org.oppia.android.util.math.MathParsingError.FunctionNameIncompleteError
import org.oppia.android.util.math.MathParsingError.GenericError
import org.oppia.android.util.math.MathParsingError.HangingSquareRootError
import org.oppia.android.util.math.MathParsingError.InvalidFunctionInUseError
import org.oppia.android.util.math.MathParsingError.MultipleRedundantParenthesesError
import org.oppia.android.util.math.MathParsingError.NestedExponentsError
import org.oppia.android.util.math.MathParsingError.NoVariableOrNumberAfterBinaryOperatorError
import org.oppia.android.util.math.MathParsingError.NoVariableOrNumberBeforeBinaryOperatorError
import org.oppia.android.util.math.MathParsingError.NumberAfterVariableError
import org.oppia.android.util.math.MathParsingError.RedundantParenthesesForIndividualTermsError
import org.oppia.android.util.math.MathParsingError.SingleRedundantParenthesesError
import org.oppia.android.util.math.MathParsingError.SpacesBetweenNumbersError
import org.oppia.android.util.math.MathParsingError.SubsequentBinaryOperatorsError
import org.oppia.android.util.math.MathParsingError.SubsequentUnaryOperatorsError
import org.oppia.android.util.math.MathParsingError.TermDividedByZeroError
import org.oppia.android.util.math.MathParsingError.UnbalancedParenthesesError
import org.oppia.android.util.math.MathParsingError.UnnecessarySymbolsError
import org.oppia.android.util.math.MathParsingError.VariableInNumericExpressionError
import org.oppia.android.util.math.MathTokenizer.Companion.BinaryOperatorToken
import org.oppia.android.util.math.MathTokenizer.Companion.Token
import org.oppia.android.util.math.MathTokenizer.Companion.Token.DivideSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.EqualsSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.ExponentiationSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.FunctionName
import org.oppia.android.util.math.MathTokenizer.Companion.Token.IncompleteFunctionName
import org.oppia.android.util.math.MathTokenizer.Companion.Token.InvalidToken
import org.oppia.android.util.math.MathTokenizer.Companion.Token.LeftParenthesisSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.MinusSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.MultiplySymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.PlusSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.PositiveInteger
import org.oppia.android.util.math.MathTokenizer.Companion.Token.PositiveRealNumber
import org.oppia.android.util.math.MathTokenizer.Companion.Token.RightParenthesisSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.SquareRootSymbol
import org.oppia.android.util.math.MathTokenizer.Companion.Token.VariableName
import kotlin.math.absoluteValue

class MathExpressionParser private constructor(private val parseContext: ParseContext) {
  // TODO:
  //  - Add helpers to reduce overall parser length.
  //  - Integrate with new errors & update the routines to not rely on exceptions except in actual exceptional cases. Make sure optional errors can be disabled (for testing purposes).
  //  - Rename this to be a generic parser, update the public API, add documentation, remove the old classes, and split up the big test routines into actual separate tests.

  // TODO: implement specific errors.
  // TODO: verify remaining GenericErrors are correct.

  // TODO: document that 'generic' means either 'numeric' or 'algebraic' (ie that the expression is syntactically the same between both grammars).
  // TODO: document that one design goal is keeping the grammar for this parser as LL(1) & why.

  private fun parseGenericEquationGrammar(): MathParsingResult<MathEquation> {
    // generic_equation_grammar = generic_equation ;
    return parseGenericEquation().maybeFail { equation ->
      checkForLearnerErrors(equation.leftSide) ?: checkForLearnerErrors(equation.rightSide)
    }
  }

  private fun parseGenericExpressionGrammar(): MathParsingResult<MathExpression> {
    // generic_expression_grammar = generic_expression ;
    return parseGenericExpression().maybeFail { expression -> checkForLearnerErrors(expression) }
  }

  private fun parseGenericEquation(): MathParsingResult<MathEquation> {
    // algebraic_equation = generic_expression , equals_operator , generic_expression ;

    if (parseContext.hasNextTokenOfType<EqualsSymbol>()) {
      // If equals starts the string, then there's no LHS.
      return EquationMissingLhsOrRhsError.toFailure()
    }

    val lhsResult = parseGenericExpression().also {
      parseContext.consumeTokenOfType<EqualsSymbol>()
    }.maybeFail {
      if (!parseContext.hasMoreTokens()) {
        // If there are no tokens following the equals symbol, then there's no RHS.
        EquationMissingLhsOrRhsError
      } else null
    }

    val rhsResult = lhsResult.flatMap { parseGenericExpression() }
    return lhsResult.combineWith(rhsResult) { lhs, rhs ->
      MathEquation.newBuilder().apply {
        leftSide = lhs
        rightSide = rhs
      }.build()
    }
  }

  private fun parseGenericExpression(): MathParsingResult<MathExpression> {
    // generic_expression = generic_add_sub_expression ;
    return parseGenericAddSubExpression()
  }

  private fun parseGenericAddSubExpression(): MathParsingResult<MathExpression> {
    // generic_add_sub_expression =
    //     generic_mult_div_expression , { generic_add_sub_expression_rhs } ;
    return parseGenericBinaryExpression(
      parseLhs = this::parseGenericMultDivExpression
    ) { nextToken ->
      // generic_add_sub_expression_rhs =
      //     generic_add_expression_rhs | generic_sub_expression_rhs ;
      when (nextToken) {
        is PlusSymbol -> BinaryOperationRhs(
          operator = ADD,
          rhsResult = parseGenericAddExpressionRhs()
        )
        is MinusSymbol -> BinaryOperationRhs(
          operator = SUBTRACT,
          rhsResult = parseGenericSubExpressionRhs()
        )
        is PositiveInteger, is PositiveRealNumber, is DivideSymbol, is EqualsSymbol,
        is ExponentiationSymbol, is FunctionName, is InvalidToken, is LeftParenthesisSymbol,
        is MultiplySymbol, is RightParenthesisSymbol, is SquareRootSymbol, is VariableName,
        is IncompleteFunctionName, null -> null
      }
    }
  }

  private fun parseGenericAddExpressionRhs(): MathParsingResult<MathExpression> {
    // generic_add_expression_rhs = plus_operator , generic_mult_div_expression ;
    return parseContext.consumeTokenOfType<PlusSymbol>().maybeFail {
      if (!parseContext.hasMoreTokens()) {
        NoVariableOrNumberAfterBinaryOperatorError(ADD)
      } else null
    }.flatMap {
      parseGenericMultDivExpression()
    }
  }

  private fun parseGenericSubExpressionRhs(): MathParsingResult<MathExpression> {
    // generic_sub_expression_rhs = minus_operator , generic_mult_div_expression ;
    return parseContext.consumeTokenOfType<MinusSymbol>().maybeFail {
      if (!parseContext.hasMoreTokens()) {
        NoVariableOrNumberAfterBinaryOperatorError(SUBTRACT)
      } else null
    }.flatMap {
      parseGenericMultDivExpression()
    }
  }

  private fun parseGenericMultDivExpression(): MathParsingResult<MathExpression> {
    // generic_mult_div_expression =
    //     generic_exp_expression , { generic_mult_div_expression_rhs } ;
    return parseGenericBinaryExpression(
      parseLhs = this::parseGenericExpExpression
    ) { nextToken ->
      // generic_mult_div_expression_rhs =
      //     generic_mult_expression_rhs
      //     | generic_div_expression_rhs
      //     | generic_implicit_mult_expression_rhs ;
      when (nextToken) {
        is MultiplySymbol -> BinaryOperationRhs(
          operator = MULTIPLY,
          rhsResult = parseGenericMultExpressionRhs()
        )
        is DivideSymbol -> BinaryOperationRhs(
          operator = DIVIDE,
          rhsResult = parseGenericDivExpressionRhs()
        )
        is FunctionName, is LeftParenthesisSymbol, is SquareRootSymbol -> BinaryOperationRhs(
          operator = MULTIPLY,
          rhsResult = parseGenericImplicitMultExpressionRhs(),
          isImplicit = true
        )
        is VariableName -> {
          if (parseContext is AlgebraicExpressionContext) {
            BinaryOperationRhs(
              operator = MULTIPLY,
              rhsResult = parseGenericImplicitMultExpressionRhs(),
              isImplicit = true
            )
          } else null
        }
        // Not a match to the expression.
        is PositiveInteger, is PositiveRealNumber, is EqualsSymbol, is ExponentiationSymbol,
        is InvalidToken, is MinusSymbol, is PlusSymbol, is RightParenthesisSymbol,
        is IncompleteFunctionName, null -> null
      }
    }
  }

  private fun parseGenericMultExpressionRhs(): MathParsingResult<MathExpression> {
    // generic_mult_expression_rhs = multiplication_operator , generic_exp_expression ;
    return parseContext.consumeTokenOfType<MultiplySymbol>().maybeFail {
      if (!parseContext.hasMoreTokens()) {
        NoVariableOrNumberAfterBinaryOperatorError(MULTIPLY)
      } else null
    }.flatMap {
      parseGenericExpExpression()
    }
  }

  private fun parseGenericDivExpressionRhs(): MathParsingResult<MathExpression> {
    // generic_div_expression_rhs = division_operator , generic_exp_expression ;
    return parseContext.consumeTokenOfType<DivideSymbol>().maybeFail {
      if (!parseContext.hasMoreTokens()) {
        NoVariableOrNumberAfterBinaryOperatorError(DIVIDE)
      } else null
    }.flatMap {
      parseGenericExpExpression()
    }
  }

  private fun parseGenericImplicitMultExpressionRhs(): MathParsingResult<MathExpression> {
    // generic_implicit_mult_expression_rhs is either numeric_implicit_mult_expression_rhs or
    // algebraic_implicit_mult_or_exp_expression_rhs depending on the current parser context.
    return when (parseContext) {
      is NumericExpressionContext -> parseNumericImplicitMultExpressionRhs()
      is AlgebraicExpressionContext -> parseAlgebraicImplicitMultOrExpExpressionRhs()
    }
  }

  private fun parseNumericImplicitMultExpressionRhs(): MathParsingResult<MathExpression> {
    // numeric_implicit_mult_expression_rhs = generic_term_without_unary_without_number ;
    return parseGenericTermWithoutUnaryWithoutNumber()
  }

  private fun parseAlgebraicImplicitMultOrExpExpressionRhs(): MathParsingResult<MathExpression> {
    // algebraic_implicit_mult_or_exp_expression_rhs =
    //     generic_term_without_unary_without_number , [ generic_exp_expression_tail ] ;
    val possibleLhs = parseGenericTermWithoutUnaryWithoutNumber()
    return if (parseContext.hasNextTokenOfType<ExponentiationSymbol>()) {
      parseGenericExpExpressionTail(possibleLhs)
    } else possibleLhs
  }

  private fun parseGenericExpExpression(): MathParsingResult<MathExpression> {
    // generic_exp_expression = generic_term_with_unary , [ generic_exp_expression_tail ] ;
    val possibleLhs = parseGenericTermWithUnary()
    return if (parseContext.hasNextTokenOfType<ExponentiationSymbol>()) {
      parseGenericExpExpressionTail(possibleLhs)
    } else possibleLhs
  }

  // Use tail recursion so that the last exponentiation is evaluated first, and right-to-left
  // associativity can be kept via backtracking.
  private fun parseGenericExpExpressionTail(
    lhsResult: MathParsingResult<MathExpression>
  ): MathParsingResult<MathExpression> {
    // generic_exp_expression_tail = exponentiation_operator , generic_exp_expression ;
    return BinaryOperationRhs(
      operator = EXPONENTIATE,
      rhsResult = lhsResult.flatMap {
        parseContext.consumeTokenOfType<ExponentiationSymbol>()
      }.maybeFail {
        if (!parseContext.hasMoreTokens()) {
          NoVariableOrNumberAfterBinaryOperatorError(EXPONENTIATE)
        } else null
      }.flatMap {
        parseGenericExpExpression()
      }
    ).computeBinaryOperationExpression(lhsResult)
  }

  private fun parseGenericTermWithUnary(): MathParsingResult<MathExpression> {
    // generic_term_with_unary =
    //    number | generic_term_without_unary_without_number | generic_plus_minus_unary_term ;
    return when (val nextToken = parseContext.peekToken()) {
      is MinusSymbol, is PlusSymbol -> parseGenericPlusMinusUnaryTerm()
      is PositiveInteger, is PositiveRealNumber -> parseNumber().takeUnless {
        parseContext.hasNextTokenOfType<PositiveInteger>() ||
          parseContext.hasNextTokenOfType<PositiveRealNumber>()
      } ?: SpacesBetweenNumbersError.toFailure()
      is FunctionName, is LeftParenthesisSymbol, is SquareRootSymbol ->
        parseGenericTermWithoutUnaryWithoutNumber()
      is VariableName -> {
        if (parseContext is AlgebraicExpressionContext) {
          parseGenericTermWithoutUnaryWithoutNumber()
        } else VariableInNumericExpressionError.toFailure()
      }
      is DivideSymbol, is ExponentiationSymbol, is MultiplySymbol -> {
        val previousToken = parseContext.getPreviousToken()
        when {
          previousToken is BinaryOperatorToken -> {
            SubsequentBinaryOperatorsError(
              operator1 = parseContext.extractSubexpression(previousToken),
              operator2 = parseContext.extractSubexpression(nextToken)
            ).toFailure()
          }
          nextToken is BinaryOperatorToken -> {
            NoVariableOrNumberBeforeBinaryOperatorError(
              operator = nextToken.getBinaryOperator()
            ).toFailure()
          }
          else -> GenericError.toFailure()
        }
      }
      is EqualsSymbol -> {
        if (parseContext is AlgebraicExpressionContext && parseContext.isPartOfEquation) {
          EquationHasWrongNumberOfEqualsError.toFailure()
        } else GenericError.toFailure()
      }
      is IncompleteFunctionName -> nextToken.toFailure()
      is InvalidToken -> nextToken.toFailure()
      is RightParenthesisSymbol, null -> GenericError.toFailure()
    }
  }

  private fun parseGenericTermWithoutUnaryWithoutNumber(): MathParsingResult<MathExpression> {
    // generic_term_without_unary_without_number is either numeric_term_without_unary_without_number
    // or algebraic_term_without_unary_without_number based the current parser context.
    return when (parseContext) {
      is NumericExpressionContext -> parseNumericTermWithoutUnaryWithoutNumber()
      is AlgebraicExpressionContext -> parseAlgebraicTermWithoutUnaryWithoutNumber()
    }
  }

  private fun parseNumericTermWithoutUnaryWithoutNumber(): MathParsingResult<MathExpression> {
    // numeric_term_without_unary_without_number =
    //     generic_function_expression | generic_group_expression | generic_rooted_term ;
    return when (val nextToken = parseContext.peekToken()) {
      is FunctionName -> parseGenericFunctionExpression()
      is LeftParenthesisSymbol -> parseGenericGroupExpression()
      is SquareRootSymbol -> parseGenericRootedTerm()
      is VariableName -> VariableInNumericExpressionError.toFailure()
      is PositiveInteger, is PositiveRealNumber, is DivideSymbol, is EqualsSymbol,
      is ExponentiationSymbol, is MinusSymbol, is MultiplySymbol, is PlusSymbol,
      is RightParenthesisSymbol, null -> GenericError.toFailure()
      is IncompleteFunctionName -> nextToken.toFailure()
      is InvalidToken -> nextToken.toFailure()
    }
  }

  private fun parseAlgebraicTermWithoutUnaryWithoutNumber(): MathParsingResult<MathExpression> {
    // algebraic_term_without_unary_without_number =
    //     generic_function_expression | generic_group_expression | generic_rooted_term | variable ;
    return when (val nextToken = parseContext.peekToken()) {
      is FunctionName -> parseGenericFunctionExpression()
      is LeftParenthesisSymbol -> parseGenericGroupExpression()
      is SquareRootSymbol -> parseGenericRootedTerm()
      is VariableName -> parseVariable()
      is PositiveInteger, is PositiveRealNumber, is DivideSymbol, is EqualsSymbol,
      is ExponentiationSymbol, is MinusSymbol, is MultiplySymbol, is PlusSymbol,
      is RightParenthesisSymbol, null -> GenericError.toFailure()
      is IncompleteFunctionName -> nextToken.toFailure()
      is InvalidToken -> nextToken.toFailure()
    }
  }

  private fun parseGenericFunctionExpression(): MathParsingResult<MathExpression> {
    // generic_function_expression = function_name , left_paren , generic_expression , right_paren ;
    val funcNameResult =
      parseContext.consumeTokenOfType<FunctionName>().maybeFail { functionName ->
        when {
          !functionName.isAllowedFunction -> InvalidFunctionInUseError(functionName.parsedName)
          functionName.parsedName == "sqrt" -> null
          else -> GenericError
        }
      }.also {
        parseContext.consumeTokenOfType<LeftParenthesisSymbol>()
      }
    val argResult = funcNameResult.flatMap { parseGenericExpression() }
    val rightParenResult =
      argResult.flatMap {
        parseContext.consumeTokenOfType<RightParenthesisSymbol> { UnbalancedParenthesesError }
      }
    return funcNameResult.combineWith(argResult, rightParenResult) { funcName, arg, rightParen ->
      MathExpression.newBuilder().apply {
        parseStartIndex = funcName.startIndex
        parseEndIndex = rightParen.endIndex
        functionCall = MathFunctionCall.newBuilder().apply {
          functionType = MathFunctionCall.FunctionType.SQUARE_ROOT
          argument = arg
        }.build()
      }.build()
    }
  }

  private fun parseGenericGroupExpression(): MathParsingResult<MathExpression> {
    // generic_group_expression = left_paren , generic_expression , right_paren ;
    val leftParenResult = parseContext.consumeTokenOfType<LeftParenthesisSymbol>()
    val expResult =
      leftParenResult.flatMap {
        if (parseContext.hasMoreTokens()) {
          parseGenericExpression()
        } else UnbalancedParenthesesError.toFailure()
      }
    val rightParenResult =
      expResult.flatMap {
        parseContext.consumeTokenOfType<RightParenthesisSymbol> { UnbalancedParenthesesError }
      }
    return leftParenResult.combineWith(expResult, rightParenResult) { leftParen, exp, rightParen ->
      MathExpression.newBuilder().apply {
        parseStartIndex = leftParen.startIndex
        parseEndIndex = rightParen.endIndex
        group = exp
      }.build()
    }
  }

  private fun parseGenericPlusMinusUnaryTerm(): MathParsingResult<MathExpression> {
    // generic_plus_minus_unary_term = generic_negated_term | generic_positive_term ;
    return when (val nextToken = parseContext.peekToken()) {
      is MinusSymbol -> parseGenericNegatedTerm()
      is PlusSymbol -> parseGenericPositiveTerm()
      is PositiveInteger, is PositiveRealNumber, is DivideSymbol, is EqualsSymbol,
      is ExponentiationSymbol, is FunctionName, is LeftParenthesisSymbol, is MultiplySymbol,
      is RightParenthesisSymbol, is SquareRootSymbol, is VariableName, null ->
        GenericError.toFailure()
      is IncompleteFunctionName -> nextToken.toFailure()
      is InvalidToken -> nextToken.toFailure()
    }
  }

  private fun parseGenericNegatedTerm(): MathParsingResult<MathExpression> {
    // generic_negated_term = minus_operator , generic_mult_div_expression ;
    val minusResult = parseContext.consumeTokenOfType<MinusSymbol>()
    val expResult = minusResult.flatMap { parseGenericMultDivExpression() }
    return minusResult.combineWith(expResult) { minus, op ->
      MathExpression.newBuilder().apply {
        parseStartIndex = minus.startIndex
        parseEndIndex = op.parseEndIndex
        unaryOperation = MathUnaryOperation.newBuilder().apply {
          operator = NEGATE
          operand = op
        }.build()
      }.build()
    }
  }

  private fun parseGenericPositiveTerm(): MathParsingResult<MathExpression> {
    // generic_positive_term = plus_operator , generic_mult_div_expression ;
    val plusResult = parseContext.consumeTokenOfType<PlusSymbol>()
    val expResult = plusResult.flatMap { parseGenericMultDivExpression() }
    return plusResult.combineWith(expResult) { plus, op ->
      MathExpression.newBuilder().apply {
        parseStartIndex = plus.startIndex
        parseEndIndex = op.parseEndIndex
        unaryOperation = MathUnaryOperation.newBuilder().apply {
          operator = POSITIVE
          operand = op
        }.build()
      }.build()
    }
  }

  private fun parseGenericRootedTerm(): MathParsingResult<MathExpression> {
    // generic_rooted_term = square_root_operator , generic_term_with_unary ;
    val sqrtResult =
      parseContext.consumeTokenOfType<SquareRootSymbol>().maybeFail {
        if (!parseContext.hasMoreTokens()) HangingSquareRootError else null
      }
    val expResult = sqrtResult.flatMap { parseGenericTermWithUnary() }
    return sqrtResult.combineWith(expResult) { sqrtSymbol, op ->
      MathExpression.newBuilder().apply {
        parseStartIndex = sqrtSymbol.startIndex
        parseEndIndex = op.parseEndIndex
        functionCall = MathFunctionCall.newBuilder().apply {
          functionType = MathFunctionCall.FunctionType.SQUARE_ROOT
          argument = op
        }.build()
      }.build()
    }
  }

  private fun parseNumber(): MathParsingResult<MathExpression> {
    // number = positive_real_number | positive_integer ;
    return when (val nextToken = parseContext.peekToken()) {
      is PositiveInteger -> {
        parseContext.consumeTokenOfType<PositiveInteger>().map { positiveInteger ->
          MathExpression.newBuilder().apply {
            parseStartIndex = positiveInteger.startIndex
            parseEndIndex = positiveInteger.endIndex
            constant = positiveInteger.toReal()
          }.build()
        }
      }
      is PositiveRealNumber -> {
        parseContext.consumeTokenOfType<PositiveRealNumber>().map { positiveRealNumber ->
          MathExpression.newBuilder().apply {
            parseStartIndex = positiveRealNumber.startIndex
            parseEndIndex = positiveRealNumber.endIndex
            constant = positiveRealNumber.toReal()
          }.build()
        }
      }
      is DivideSymbol, is EqualsSymbol, is ExponentiationSymbol, is FunctionName,
      is LeftParenthesisSymbol, is MinusSymbol, is MultiplySymbol, is PlusSymbol,
      is RightParenthesisSymbol, is SquareRootSymbol, is VariableName, null ->
        GenericError.toFailure()
      is IncompleteFunctionName -> nextToken.toFailure()
      is InvalidToken -> nextToken.toFailure()
    }
  }

  private fun parseVariable(): MathParsingResult<MathExpression> {
    val variableNameResult =
      parseContext.consumeTokenOfType<VariableName>().maybeFail {
        if (!parseContext.allowsVariables()) GenericError else null
      }.maybeFail { variableName ->
        return@maybeFail if (parseContext.hasMoreTokens()) {
          when (val nextToken = parseContext.peekToken()) {
            is PositiveInteger ->
              NumberAfterVariableError(nextToken.toReal(), variableName.parsedName)
            is PositiveRealNumber ->
              NumberAfterVariableError(nextToken.toReal(), variableName.parsedName)
            else -> null
          }
        } else null
      }
    return variableNameResult.map { variableName ->
      MathExpression.newBuilder().apply {
        parseStartIndex = variableName.startIndex
        parseEndIndex = variableName.endIndex
        variable = variableName.parsedName
      }.build()
    }
  }

  private fun parseGenericBinaryExpression(
    parseLhs: () -> MathParsingResult<MathExpression>,
    parseRhs: (Token?) -> BinaryOperationRhs?
  ): MathParsingResult<MathExpression> {
    var lastLhsResult = parseLhs()
    while (!lastLhsResult.isFailure()) {
      // Compute the next LHS if there are further RHS expressions.
      lastLhsResult =
        parseRhs(parseContext.peekToken())
        ?.computeBinaryOperationExpression(lastLhsResult)
        ?: break // Not a match to the expression.
    }
    return lastLhsResult
  }

  private fun checkForLearnerErrors(expression: MathExpression): MathParsingError? {
    val firstMultiRedundantGroup = expression.findFirstMultiRedundantGroup()
    val nextRedundantGroup = expression.findNextRedundantGroup()
    val nextUnaryOperation = expression.findNextRedundantUnaryOperation()
    val nextExpWithVariableExp = expression.findNextExponentiationWithVariablePower()
    val nextExpWithTooLargePower = expression.findNextExponentiationWithTooLargePower()
    val nextExpWithNestedExp = expression.findNextNestedExponentiation()
    val nextDivByZero = expression.findNextDivisionByZero()
    val disallowedVariables = expression.findAllDisallowedVariables(parseContext)
    // Note that the order of checks here is important since errors have precedence, and some are
    // redundant and, in the wrong order, may cause the wrong error to be returned.
    val includeOptionalErrors = parseContext.errorCheckingMode.includesOptionalErrors()
    return when {
      includeOptionalErrors && firstMultiRedundantGroup != null -> {
        val subExpression = parseContext.extractSubexpression(firstMultiRedundantGroup)
        MultipleRedundantParenthesesError(subExpression, firstMultiRedundantGroup)
      }
      includeOptionalErrors && expression.expressionTypeCase == GROUP ->
        SingleRedundantParenthesesError(parseContext.rawExpression, expression)
      includeOptionalErrors && nextRedundantGroup != null -> {
        val subExpression = parseContext.extractSubexpression(nextRedundantGroup)
        RedundantParenthesesForIndividualTermsError(subExpression, nextRedundantGroup)
      }
      includeOptionalErrors && nextUnaryOperation != null -> SubsequentUnaryOperatorsError
      includeOptionalErrors && nextExpWithVariableExp != null -> ExponentIsVariableExpressionError
      includeOptionalErrors && nextExpWithTooLargePower != null -> ExponentTooLargeError
      includeOptionalErrors && nextExpWithNestedExp != null -> NestedExponentsError
      includeOptionalErrors && nextDivByZero != null -> TermDividedByZeroError
      includeOptionalErrors && disallowedVariables.isNotEmpty() ->
        DisabledVariablesInUseError(disallowedVariables.toList())
      else -> ensureNoRemainingTokens()
    }
  }

  private fun ensureNoRemainingTokens(): MathParsingError? {
    // Make sure all tokens were consumed (otherwise there are trailing tokens which invalidate the
    // whole grammar).
    return if (parseContext.hasMoreTokens()) {
      when (val nextToken = parseContext.peekToken()) {
        is LeftParenthesisSymbol, is RightParenthesisSymbol -> UnbalancedParenthesesError
        is EqualsSymbol -> {
          if (parseContext is AlgebraicExpressionContext && parseContext.isPartOfEquation) {
            EquationHasWrongNumberOfEqualsError
          } else GenericError
        }
        is IncompleteFunctionName -> nextToken.toError()
        is InvalidToken -> nextToken.toError()
        is PositiveInteger, is PositiveRealNumber, is DivideSymbol, is ExponentiationSymbol,
        is FunctionName, is MinusSymbol, is MultiplySymbol, is PlusSymbol, is SquareRootSymbol,
        is VariableName, null -> GenericError
      }
    } else null
  }

  private fun PositiveInteger.toReal(): Real = Real.newBuilder().apply {
    integer = parsedValue
  }.build()

  private fun PositiveRealNumber.toReal(): Real = Real.newBuilder().apply {
    irrational = parsedValue
  }.build()

  @Suppress("unused") // The receiver is behaving as a namespace.
  private fun IncompleteFunctionName.toError(): MathParsingError = FunctionNameIncompleteError

  private fun InvalidToken.toError(): MathParsingError =
    UnnecessarySymbolsError(parseContext.extractSubexpression(this))

  private fun <T> IncompleteFunctionName.toFailure(): MathParsingResult<T> = toError().toFailure()

  private fun <T> InvalidToken.toFailure(): MathParsingResult<T> = toError().toFailure()

  private sealed class ParseContext(val rawExpression: String) {
    val tokens: PeekableIterator<Token> by lazy {
      PeekableIterator.fromSequence(MathTokenizer.tokenize(rawExpression))
    }
    private var previousToken: Token? = null

    abstract val errorCheckingMode: ErrorCheckingMode

    abstract fun allowsVariables(): Boolean

    fun hasMoreTokens(): Boolean = tokens.hasNext()

    fun peekToken(): Token? = tokens.peek()

    /**
     * Returns the last token consumed by [consumeTokenOfType], or null if none. Note: this should
     * only be used for error reporting purposes, not for parsing. Using this for parsing would, in
     * certain cases, allow for a non-LL(1) grammar which is against one design goal for this
     * parser.
     */
    fun getPreviousToken(): Token? = previousToken

    inline fun <reified T : Token> hasNextTokenOfType(): Boolean = peekToken() is T

    inline fun <reified T : Token> consumeTokenOfType(
      missingError: () -> MathParsingError = { GenericError }
    ): MathParsingResult<T> {
      val maybeToken = tokens.expectNextMatches { it is T } as? T
      return maybeToken?.let { token ->
        previousToken = token
        MathParsingResult.Success(token)
      } ?: missingError().toFailure()
    }

    fun extractSubexpression(token: Token): String {
      return rawExpression.substring(token.startIndex, token.endIndex)
    }

    fun extractSubexpression(expression: MathExpression): String {
      return rawExpression.substring(expression.parseStartIndex, expression.parseEndIndex)
    }

    class NumericExpressionContext(
      rawExpression: String,
      override val errorCheckingMode: ErrorCheckingMode
    ) : ParseContext(rawExpression) {
      // Numeric expressions never allow variables.
      override fun allowsVariables(): Boolean = false
    }

    class AlgebraicExpressionContext(
      rawExpression: String,
      val isPartOfEquation: Boolean,
      private val allowedVariables: List<String>,
      override val errorCheckingMode: ErrorCheckingMode
    ) : ParseContext(rawExpression) {
      fun allowsVariable(variableName: String): Boolean = variableName in allowedVariables

      override fun allowsVariables(): Boolean = true
    }
  }

  companion object {
    enum class ErrorCheckingMode {
      REQUIRED_ONLY,
      ALL_ERRORS
    }

    sealed class MathParsingResult<T> {
      data class Success<T>(val result: T) : MathParsingResult<T>()

      data class Failure<T>(val error: MathParsingError) : MathParsingResult<T>()
    }

    fun parseNumericExpression(
      rawExpression: String,
      errorCheckingMode: ErrorCheckingMode = ErrorCheckingMode.ALL_ERRORS
    ): MathParsingResult<MathExpression> =
      createNumericParser(rawExpression, errorCheckingMode).parseGenericExpressionGrammar()

    fun parseAlgebraicExpression(
      rawExpression: String,
      allowedVariables: List<String>,
      errorCheckingMode: ErrorCheckingMode = ErrorCheckingMode.ALL_ERRORS
    ): MathParsingResult<MathExpression> {
      return createAlgebraicParser(
        rawExpression, isPartOfEquation = false, allowedVariables, errorCheckingMode
      ).parseGenericExpressionGrammar()
    }

    fun parseAlgebraicEquation(
      rawExpression: String,
      allowedVariables: List<String>,
      errorCheckingMode: ErrorCheckingMode = ErrorCheckingMode.ALL_ERRORS
    ): MathParsingResult<MathEquation> {
      return createAlgebraicParser(
        rawExpression, isPartOfEquation = true, allowedVariables, errorCheckingMode
      ).parseGenericEquationGrammar()
    }

    private fun createNumericParser(
      rawExpression: String,
      errorCheckingMode: ErrorCheckingMode
    ): MathExpressionParser =
      MathExpressionParser(NumericExpressionContext(rawExpression, errorCheckingMode))

    private fun createAlgebraicParser(
      rawExpression: String,
      isPartOfEquation: Boolean,
      allowedVariables: List<String>,
      errorCheckingMode: ErrorCheckingMode
    ): MathExpressionParser {
      return MathExpressionParser(
        AlgebraicExpressionContext(
          rawExpression, isPartOfEquation, allowedVariables, errorCheckingMode
        )
      )
    }

    private fun ErrorCheckingMode.includesOptionalErrors() = this == ErrorCheckingMode.ALL_ERRORS

    private fun <T> MathParsingError.toFailure(): MathParsingResult<T> =
      MathParsingResult.Failure(this)

    private fun <T> MathParsingResult<T>.isFailure() = this is MathParsingResult.Failure

    /**
     * Maps [this] result to a new value. Note that this lazily uses the provided function (i.e.
     * it's only used if [this] result is passing, otherwise the method will short-circuit a failure
     * state so that [this] result's failure is preserved).
     *
     * @param operation computes a new success result given the current successful result value
     * @return a new [MathParsingResult] with a successful result provided by the operation, or the
     *     preserved failure of [this] result
     */
    private fun <T1, T2> MathParsingResult<T1>.map(
      operation: (T1) -> T2
    ): MathParsingResult<T2> = flatMap { result -> MathParsingResult.Success(operation(result)) }

    /**
     * Maps [this] result to a new value. Note that this lazily uses the provided function (i.e.
     * it's only used if [this] result is passing, otherwise the method will short-circuit a failure
     * state so that [this] result's failure is preserved).
     *
     * @param operation computes a new result (either a success or failure) given the current
     *     successful result value
     * @return a new [MathParsingResult] with either a result provided by the operation, or the
     *     preserved failure of [this] result
     */
    private fun <T1, T2> MathParsingResult<T1>.flatMap(
      operation: (T1) -> MathParsingResult<T2>
    ): MathParsingResult<T2> {
      return when (this) {
        is MathParsingResult.Success -> operation(result)
        is MathParsingResult.Failure -> error.toFailure()
      }
    }

    /**
     * Potentially changes [this] result into a failure based on the provided [operation]. Note that
     * this function lazily uses the operation (i.e. it's only called if [this] result is in a
     * passing state), and the returned result will only be in a failing state if [operation]
     * returns a non-null error.
     *
     * @param operation computes a failure error, or null if no error was determined, given the
     *     current successful result value
     * @return either [this] or a failing result if [operation] was called & returned a non-null
     *     error
     */
    private fun <T> MathParsingResult<T>.maybeFail(
      operation: (T) -> MathParsingError?
    ): MathParsingResult<T> = flatMap { result -> operation(result)?.toFailure() ?: this }

    /**
     * Calls an operation if [this] operation isn't already failing, and returns a failure only if
     * that operation's result is a failure (otherwise returns [this] result). This function can be
     * useful to ensure that subsequent operations are successful even when those operations'
     * results are never directly used.
     *
     * @param operation computes a new result that, when failing, will result in a failing result
     *     returned from this function. This is only called if [this] result is currently
     *     successful.
     * @return either [this] (iff either this result is failing, or the result of [operation] is a
     *     success), or the failure returned by [operation]
     */
    private fun <T1, T2> MathParsingResult<T1>.also(
      operation: () -> MathParsingResult<T2>
    ): MathParsingResult<T1> = flatMap {
      when (val other = operation()) {
        is MathParsingResult.Success -> this
        is MathParsingResult.Failure -> other.error.toFailure()
      }
    }

    /**
     * Combines [this] result with another result, given a specific combination function.
     *
     * @param other the result to combine with [this] result
     * @param combine computes a new value given the result from [this] and [other]. Note that this
     *     is only called if both results are successful, and the corresponding successful values
     *     are provided in-order ([this] result's value is the first parameter, and [other]'s is the
     *     second).
     * @return either [this] result's or [other]'s failure, if either are failing, or a successful
     *     result containing the value computed by [combine]
     */
    private fun <O, I1, I2> MathParsingResult<I1>.combineWith(
      other: MathParsingResult<I2>,
      combine: (I1, I2) -> O,
    ): MathParsingResult<O> {
      return flatMap { result ->
        other.map { otherResult ->
          combine(result, otherResult)
        }
      }
    }

    /**
     * Performs the same operation as the other [combineWith] function, except with three
     * [MathParsingResult]s, instead.
     */
    private fun <O, I1, I2, I3> MathParsingResult<I1>.combineWith(
      other1: MathParsingResult<I2>,
      other2: MathParsingResult<I3>,
      combine: (I1, I2, I3) -> O,
    ): MathParsingResult<O> {
      return flatMap { result ->
        other1.flatMap { otherResult1 ->
          other2.map { otherResult2 ->
            combine(result, otherResult1, otherResult2)
          }
        }
      }
    }

    private data class BinaryOperationRhs(
      val operator: MathBinaryOperation.Operator,
      val rhsResult: MathParsingResult<MathExpression>,
      val isImplicit: Boolean = false
    ) {
      fun computeBinaryOperationExpression(
        lhsResult: MathParsingResult<MathExpression>
      ): MathParsingResult<MathExpression> {
        return lhsResult.combineWith(rhsResult) { lhs, rhs ->
          MathExpression.newBuilder().apply {
            parseStartIndex = lhs.parseStartIndex
            parseEndIndex = rhs.parseEndIndex
            binaryOperation = MathBinaryOperation.newBuilder().apply {
              operator = this@BinaryOperationRhs.operator
              leftOperand = lhs
              rightOperand = rhs
              isImplicit = this@BinaryOperationRhs.isImplicit
            }.build()
          }.build()
        }
      }
    }

    private fun MathExpression.findFirstMultiRedundantGroup(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          binaryOperation.leftOperand.findFirstMultiRedundantGroup()
            ?: binaryOperation.rightOperand.findFirstMultiRedundantGroup()
        }
        UNARY_OPERATION -> unaryOperation.operand.findFirstMultiRedundantGroup()
        FUNCTION_CALL -> functionCall.argument.findFirstMultiRedundantGroup()
        GROUP ->
          group.takeIf { it.expressionTypeCase == GROUP }
            ?: group.findFirstMultiRedundantGroup()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findNextRedundantGroup(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          binaryOperation.leftOperand.findNextRedundantGroup()
            ?: binaryOperation.rightOperand.findNextRedundantGroup()
        }
        UNARY_OPERATION -> unaryOperation.operand.findNextRedundantGroup()
        FUNCTION_CALL -> functionCall.argument.findNextRedundantGroup()
        GROUP -> group.takeIf {
          it.expressionTypeCase in listOf(CONSTANT, VARIABLE)
        } ?: group.findNextRedundantGroup()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findNextRedundantUnaryOperation(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          binaryOperation.leftOperand.findNextRedundantUnaryOperation()
            ?: binaryOperation.rightOperand.findNextRedundantUnaryOperation()
        }
        UNARY_OPERATION -> unaryOperation.operand.takeIf {
          it.expressionTypeCase == UNARY_OPERATION
        } ?: unaryOperation.operand.findNextRedundantUnaryOperation()
        FUNCTION_CALL -> functionCall.argument.findNextRedundantUnaryOperation()
        GROUP -> group.findNextRedundantUnaryOperation()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findNextExponentiationWithVariablePower(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          takeIf {
            binaryOperation.operator == EXPONENTIATE &&
              binaryOperation.rightOperand.isVariableExpression()
          } ?: binaryOperation.leftOperand.findNextExponentiationWithVariablePower()
            ?: binaryOperation.rightOperand.findNextExponentiationWithVariablePower()
        }
        UNARY_OPERATION -> unaryOperation.operand.findNextExponentiationWithVariablePower()
        FUNCTION_CALL -> functionCall.argument.findNextExponentiationWithVariablePower()
        GROUP -> group.findNextExponentiationWithVariablePower()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findNextExponentiationWithTooLargePower(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          takeIf {
            binaryOperation.operator == EXPONENTIATE &&
              binaryOperation.rightOperand.expressionTypeCase == CONSTANT &&
              binaryOperation.rightOperand.constant.toDouble() > 5.0
          } ?: binaryOperation.leftOperand.findNextExponentiationWithTooLargePower()
            ?: binaryOperation.rightOperand.findNextExponentiationWithTooLargePower()
        }
        UNARY_OPERATION -> unaryOperation.operand.findNextExponentiationWithTooLargePower()
        FUNCTION_CALL -> functionCall.argument.findNextExponentiationWithTooLargePower()
        GROUP -> group.findNextExponentiationWithTooLargePower()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findNextNestedExponentiation(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          takeIf {
            binaryOperation.operator == EXPONENTIATE &&
              binaryOperation.rightOperand.containsExponentiation()
          } ?: binaryOperation.leftOperand.findNextNestedExponentiation()
            ?: binaryOperation.rightOperand.findNextNestedExponentiation()
        }
        UNARY_OPERATION -> unaryOperation.operand.findNextNestedExponentiation()
        FUNCTION_CALL -> functionCall.argument.findNextNestedExponentiation()
        GROUP -> group.findNextNestedExponentiation()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findNextDivisionByZero(): MathExpression? {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          takeIf {
            binaryOperation.operator == DIVIDE &&
              binaryOperation.rightOperand.expressionTypeCase == CONSTANT &&
              binaryOperation.rightOperand.constant
                .toDouble().absoluteValue.approximatelyEquals(0.0)
          } ?: binaryOperation.leftOperand.findNextDivisionByZero()
            ?: binaryOperation.rightOperand.findNextDivisionByZero()
        }
        UNARY_OPERATION -> unaryOperation.operand.findNextDivisionByZero()
        FUNCTION_CALL -> functionCall.argument.findNextDivisionByZero()
        GROUP -> group.findNextDivisionByZero()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> null
      }
    }

    private fun MathExpression.findAllDisallowedVariables(context: ParseContext): Set<String> {
      return if (context is AlgebraicExpressionContext) {
        findAllDisallowedVariablesAux(context)
      } else setOf()
    }

    private fun MathExpression.findAllDisallowedVariablesAux(
      context: AlgebraicExpressionContext
    ): Set<String> {
      return when (expressionTypeCase) {
        VARIABLE -> if (context.allowsVariable(variable)) setOf() else setOf(variable)
        BINARY_OPERATION -> {
          binaryOperation.leftOperand.findAllDisallowedVariablesAux(context) +
            binaryOperation.rightOperand.findAllDisallowedVariablesAux(context)
        }
        UNARY_OPERATION -> unaryOperation.operand.findAllDisallowedVariablesAux(context)
        FUNCTION_CALL -> functionCall.argument.findAllDisallowedVariablesAux(context)
        GROUP -> group.findAllDisallowedVariablesAux(context)
        CONSTANT, EXPRESSIONTYPE_NOT_SET, null -> setOf()
      }
    }

    private fun MathExpression.isVariableExpression(): Boolean {
      return when (expressionTypeCase) {
        VARIABLE -> true
        BINARY_OPERATION -> {
          binaryOperation.leftOperand.isVariableExpression() ||
            binaryOperation.rightOperand.isVariableExpression()
        }
        UNARY_OPERATION -> unaryOperation.operand.isVariableExpression()
        FUNCTION_CALL -> functionCall.argument.isVariableExpression()
        GROUP -> group.isVariableExpression()
        CONSTANT, EXPRESSIONTYPE_NOT_SET, null -> false
      }
    }

    private fun MathExpression.containsExponentiation(): Boolean {
      return when (expressionTypeCase) {
        BINARY_OPERATION -> {
          binaryOperation.operator == EXPONENTIATE ||
            binaryOperation.leftOperand.containsExponentiation() ||
            binaryOperation.rightOperand.containsExponentiation()
        }
        UNARY_OPERATION -> unaryOperation.operand.containsExponentiation()
        FUNCTION_CALL -> functionCall.argument.containsExponentiation()
        GROUP -> group.containsExponentiation()
        CONSTANT, VARIABLE, EXPRESSIONTYPE_NOT_SET, null -> false
      }
    }
  }
}