package sl
import scala.util.matching.Regex
import Expressions._
import scala.annotation.tailrec
import java.io.File
import scala.io.Source
import scala.language.implicitConversions

trait Lexers {
  type Token
  type Value
  val debug = false

  /**
    * Result of matching a given rule with input prefix.
    *
    * @param tokens tokens produced by matching
    * @param state state produced by matching
    * @param inputState progression in input string
    */
  case class RuleMatch(tokens: List[Positioned[Token]], state: Value, inputState: InputState)

  /**
    * Error in the lexing process
    *
    * @param message error message
    * @param pos position where the problem appeared
    */
  case class LexerError(message: String, pos: Position) extends Error(f"$message at (${pos.line}, ${pos.column})")

  /**
    * Lexer that can produce tokens given an input string.
    *
    * @param initialState set of rules and value that can be matched at the start of input string
    * @param error token that should be produced when no matching rule can be found
    */
  case class Lexer(rules: Rule[_]*)(error: Token, initialValue: Value, finalAction: (Value, Position) => List[Positioned[Token]] = (_, _) => List()) {
    /**
      * Produces tokens using the successive rules and the given string.
      *
      * @param input string containing tokens
      * @return produced tokens if matching rules were found for the entire input, None otherwise
      */
    def tokenizeFromString(input: String): Option[List[Positioned[Token]]] = {
      val start = InputState(Position.initial, new ArrayCharSequence(input.toArray))
      if (debug) {
        println(f"# Starting tokenization on [${input.take(30).replace("\n", "\\n")}]")
      }
      advance(start, initialValue, Nil).map(s => s.reverse)
    }

    /**
      * Produces tokens using the successive rules and the given input file.
      *
      * @param path file containing tokens
      * @return produced tokens if matching rules were found for the entire input, None otherwise
      */
    def tokenizeFromFile(path: String): Option[List[Positioned[Token]]] = {
      val content = Source.fromFile(path).mkString
      tokenizeFromString(content)
    }
    
    // uses successive rules to make progress with input
    @tailrec
    private def advance(input: InputState, state: Value, acc: List[Positioned[Token]]): Option[List[Positioned[Token]]] = {
      if (input.chars.length == 0) {
        val finalTokens = finalAction(state, input.fromStart)
        Some(finalTokens.reverse ++ acc)
      } else {
        if (debug) {
          println(f"- Attempting match on [${input.chars.toString.take(15)}]")
        }
        firstMatch(input, state) match {
          case None => Some(Positioned(error, input.fromStart) :: acc)
          case Some(RuleMatch(tokens, nextState, remainingInput)) =>
            if (debug) {
              println(f"-> Produced tokens [$tokens]")
            }
            advance(remainingInput, nextState, tokens ++ acc)
        }
      }
    }

    /**
      * Tries to match the rules against the input in priority order recursively.
      *
      * @param input input against which rules must be matched
      * @param value state value before attempting to match
      * @param remainingRules rules against which matching is yet to be attempted
      * @return a RuleMatch instance if any of the rules is matching the input prefix, None otherwise
      */
    def firstMatch(input: InputState, value: Value, remainingRules: Seq[Rule[_]] = rules, best: Option[RuleMatch] = None, debug: Boolean=false): Option[RuleMatch] = remainingRules match {
      case Seq() => best match {
        case Some(_) => best
        case None => None
      }
      case r +: rs => r.tryTransition(value, input) match {
        case None => {
          if (debug) {
            //println(f"  - Rule ${r.}")
          }
          firstMatch(input, value, rs, best)
        }
        case Some(m) => {
          if (m.inputState.fromStart.index > best.map(_.inputState.fromStart.index).getOrElse(0))
            firstMatch(input, value, rs, Some(m))
          else
            firstMatch(input, value, rs, best)
        }
      }
    }
  }

  /**
    * Rule associating a regular expression with a transition function
    *
    * @param expr regular expression that is matched against the input
    */
  abstract class Rule[ExprRes](expr: CompiledExpr[ExprRes]) {
    /**
      * Tries to match the rule expression against the input.
      *
      * @param state state before the matching is attempted
      * @param input input against which matching is attempted
      * @return a RuleMatch instance if the regular expression is matching the input prefix, None otherwise
      */
    def tryTransition(state: Value, input: InputState): Option[RuleMatch] =
      expr.matchWith(input) match {
        case None =>{
          if (debug) println(f"  - No match for ${expr.re.pattern.pattern}")
          None
        } 
        case Some((value, endPos)) => {
          val (newState, producedTokens) = transitionResult(state, value, input)
          val remainingChars = input.chars.subSequence(endPos.index - input.fromStart.index, input.chars.length())
          
          if (debug){
            println(f"  - Match for ${expr.re.pattern.pattern}")
            println(f"    - Result [$value] for tokens ${producedTokens.reverse.map(_.value)}")
          } 

          Some(RuleMatch(producedTokens.reverse, newState, InputState(endPos, remainingChars)))
        }
      }
    
    // gets the result of the transition associated with the rule
    protected def transitionResult(state: Value, matchVal: ExprRes, input: InputState): (Value, List[Positioned[Token]])
  }

  type ReflectiveTransition[ExprRes] = (Value, ExprRes, Position) => (Value, List[Positioned[Token]])

  /**
    * Rule with a transition from a state to a state with the same rules as previously
    *
    * @param expr regular expression that is matched against the input
    * @param transition transition to a state with same rules as previously
    */
  case class ReflectiveRule[ExprRes](expr: CompiledExpr[ExprRes], transition: ReflectiveTransition[ExprRes]) extends Rule[ExprRes](expr) {
    override def transitionResult(state: Value, matchVal: ExprRes, input: InputState) = {
      val (newValue, producedTokens) = transition(state, matchVal, input.fromStart)
      (newValue, producedTokens)
    }
  }

  /**
    * Compiles a regular expression
    *
    * @param expr expression
    * @return compiled expression
    */
  implicit def compileExpr[A](expr: Expr[A]): CompiledExpr[A] = new CompiledExpr(expr)

  /**
    * Compiles a regular expression made of a single group
    *
    * @param expr expression
    * @return compiled expression
    */
  implicit def compileGroup(expr: Group): CompiledExpr[String] = new CompiledExpr(toExpr(expr))

  /**
    * Top-level, compiled regular expression
    *
    * @param expr underlying expression
    */
  class CompiledExpr[A](expr: Expr[A]) {
    val re = expr.build().map(s => "(" + s + ")").reduce(_ + _).r

    /**
      * The regular expression in its scala standard library form
      *
      * @return regex
      */
    def get: Regex = re

    /**
      * Tries to match the expression against the input
      *
      * @param input input against which the regex is matched
      * @return the match result and the position after the entire match in input
      */
    def matchWith(input: InputState): Option[(A, Position)] = re.findPrefixMatchOf(input.chars) match {
      case None => None
      case Some(m) => {
        val startPos = input.fromStart
        val afterPos = m.subgroups.foldLeft(startPos)((pos, str) => pos + str)
        Some(expr.transform(m.subgroups), afterPos)
      }
    }

    /**
      * Associated the expression with a transition to a state with the same set of rules to create a rule
      *
      * @param transform transformation function
      * @return resulting rule
      */
    def |>(transform: ReflectiveTransition[A]) = ReflectiveRule(this, transform)
  }
}