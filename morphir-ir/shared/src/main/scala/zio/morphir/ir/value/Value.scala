package zio.morphir.ir.value

import zio.Chunk
import zio.morphir.ir.{FQName, Name, NativeFunction, Pattern, Literal => Lit}

import scala.annotation.tailrec

sealed trait Value[+TA, +VA] { self =>
  import Value.{List => ListType, Unit => UnitType, _}

  def @@[LowerTA >: TA, UpperTA >: LowerTA, LowerVA >: VA, UpperVA >: LowerVA](
      aspect: ValueAspect[LowerTA, UpperTA, LowerVA, UpperVA]
  ): Value[LowerTA, LowerVA] =
    aspect(self)

  def attributes: VA

  def mapAttributes[TB, VB](f: TA => TB, g: VA => VB): Value[TB, VB] = self match {
    case t @ Apply(_, _, _) =>
      Apply(g(t.attributes), t.function.mapAttributes(f, g), t.arguments.map(_.mapAttributes(f, g)))
    case t @ Constructor(_, _) => Constructor(g(t.attributes), t.name)
    case t @ Destructure(_, _, _, _) =>
      Destructure(
        g(t.attributes),
        t.pattern.mapAttributes(g),
        t.valueToDestruct.mapAttributes(f, g),
        t.inValue.mapAttributes(f, g)
      )
    case t @ Field(_, _, _)      => Field(g(t.attributes), t.target.mapAttributes(f, g), t.name)
    case t @ FieldFunction(_, _) => FieldFunction(g(t.attributes), t.name)
    case t @ IfThenElse(_, _, _, _) =>
      IfThenElse(
        g(t.attributes),
        t.condition.mapAttributes(f, g),
        t.thenBranch.mapAttributes(f, g),
        t.elseBranch.mapAttributes(f, g)
      )
    case t @ Lambda(_, _, _) => Lambda(g(t.attributes), t.argumentPattern.mapAttributes(g), t.body.mapAttributes(f, g))
    case t @ LetDefinition(_, _, _, _) =>
      LetDefinition(g(t.attributes), t.valueName, t.valueDefinition.mapAttributes(f, g), t.inValue.mapAttributes(f, g))
    case t @ LetRecursion(_, _, _) =>
      LetRecursion(
        g(t.attributes),
        t.valueDefinitions.map { case (name, definition) => (name, definition.mapAttributes(f, g)) },
        t.inValue.mapAttributes(f, g)
      )
    case t @ ListType(_, _)       => ListType(g(t.attributes), t.elements.map(_.mapAttributes(f, g)))
    case t @ Literal(_, _)        => Literal(g(t.attributes), t.literal)
    case t @ NativeApply(_, _, _) => NativeApply(g(t.attributes), t.function, t.arguments.map(_.mapAttributes(f, g)))
    case t @ PatternMatch(_, _, _) =>
      PatternMatch(
        g(t.attributes),
        t.branchOutOn.mapAttributes(f, g),
        t.cases.map { case (p, v) => (p.mapAttributes(g), v.mapAttributes(f, g)) }
      )
    case t @ Record(_, _)    => Record(g(t.attributes), t.fields.map { case (n, v) => (n, v.mapAttributes(f, g)) })
    case t @ Reference(_, _) => Reference(g(t.attributes), t.name)
    case t @ Tuple(_, _)     => Tuple(g(t.attributes), t.elements.map(item => item.mapAttributes(f, g)))
    case t @ UnitType(_)     => UnitType(g(t.attributes))
    case t @ UpdateRecord(_, _, _) =>
      UpdateRecord(
        g(t.attributes),
        t.valueToUpdate.mapAttributes(f, g),
        t.fieldsToUpdate.map { case (n, v) => (n, v.mapAttributes(f, g)) }
      )
    case t @ Variable(_, _) => Variable(g(t.attributes), t.name)
  }

  def collectVariables: Set[Name] = foldLeft(Set.empty[Name]) {
    case (acc, Variable(_, name)) => acc + name
    case (acc, _)                 => acc
  }

  def collectReferences: Set[FQName] = foldLeft(Set.empty[FQName]) {
    case (acc, Reference(_, name)) => acc + name
    case (acc, _)                  => acc
  }

  // def indexedMapValue[VB](initial: Int)(f: (Int, VA) => VB): (Value[TA, VB], Int) = ???
  def rewrite[TB >: TA, VB >: VA](pf: PartialFunction[Value[TB, VB], Value[TB, VB]]): Value[TB, VB] =
    transform[TB, VB](v => pf.lift(v).getOrElse(v))

  def transform[TB >: TA, VB >: VA](f: Value[TB, VB] => Value[TB, VB]): Value[TB, VB] = fold[Value[TB, VB]](
    applyCase = (attributes, function, arguments) => f(Apply(attributes, function, arguments)),
    constructorCase = (attributes, name) => f(Constructor(attributes, name)),
    destructureCase =
      (attributes, pattern, valueToDestruct, inValue) => f(Destructure(attributes, pattern, valueToDestruct, inValue)),
    fieldCase = (attributes, target, name) => f(Field(attributes, target, name)),
    fieldFunctionCase = (attributes, name) => f(FieldFunction(attributes, name)),
    ifThenElseCase =
      (attributes, condition, thenBranch, elseBranch) => f(IfThenElse(attributes, condition, thenBranch, elseBranch)),
    lambdaCase = (attributes, argumentPattern, body) => f(Lambda(attributes, argumentPattern, body)),
    letDefinitionCase = (attributes, valueName, valueDefinition, inValue) =>
      f(LetDefinition(attributes, valueName, valueDefinition.toDefinition, inValue)),
    letRecursionCase = (attributes, valueDefinitions, inValue) =>
      f(LetRecursion(attributes, valueDefinitions.map { case (n, d) => (n, d.toDefinition) }, inValue)),
    listCase = (attributes, elements) => f(ListType(attributes, elements)),
    literalCase = (attributes, literal) => f(Literal(attributes, literal)),
    nativeApplyCase = (attributes, function, arguments) => f(NativeApply(attributes, function, arguments)),
    patternMatchCase = (attributes, branchOutOn, cases) => f(PatternMatch(attributes, branchOutOn, cases)),
    recordCase = (attributes, fields) => f(Record(attributes, fields)),
    referenceCase = (attributes, name) => f(Reference(attributes, name)),
    tupleCase = (attributes, elements) => f(Tuple(attributes, elements)),
    unitCase = (attributes) => f(UnitType(attributes)),
    updateRecordCase =
      (attributes, valueToUpdate, fieldsToUpdate) => f(UpdateRecord(attributes, valueToUpdate, fieldsToUpdate)),
    variableCase = (attributes, name) => f(Variable(attributes, name))
  )

  def fold[Z](
      applyCase: (VA, Z, Chunk[Z]) => Z,
      constructorCase: (VA, FQName) => Z,
      destructureCase: (VA, Pattern[VA], Z, Z) => Z,
      fieldCase: (VA, Z, Name) => Z,
      fieldFunctionCase: (VA, Name) => Z,
      ifThenElseCase: (VA, Z, Z, Z) => Z,
      lambdaCase: (VA, Pattern[VA], Z) => Z,
      letDefinitionCase: (VA, Name, Definition.Case[TA, VA, Z], Z) => Z,
      letRecursionCase: (VA, Map[Name, Definition.Case[TA, VA, Z]], Z) => Z,
      listCase: (VA, Chunk[Z]) => Z,
      literalCase: (VA, Lit[_]) => Z,
      nativeApplyCase: (VA, NativeFunction, Chunk[Z]) => Z,
      patternMatchCase: (VA, Z, Chunk[(Pattern[VA], Z)]) => Z,
      recordCase: (VA, Chunk[(Name, Z)]) => Z,
      referenceCase: (VA, FQName) => Z,
      tupleCase: (VA, Chunk[Z]) => Z,
      unitCase: VA => Z,
      updateRecordCase: (VA, Z, Chunk[(Name, Z)]) => Z,
      variableCase: (VA, Name) => Z
  ): Z = self match {
    case Apply(attributes, function, arguments) =>
      applyCase(
        attributes,
        function.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        arguments.map(
          _.fold(
            applyCase,
            constructorCase,
            destructureCase,
            fieldCase,
            fieldFunctionCase,
            ifThenElseCase,
            lambdaCase,
            letDefinitionCase,
            letRecursionCase,
            listCase,
            literalCase,
            nativeApplyCase,
            patternMatchCase,
            recordCase,
            referenceCase,
            tupleCase,
            unitCase,
            updateRecordCase,
            variableCase
          )
        )
      )
    case Constructor(attributes, name) => constructorCase(attributes, name)
    case Destructure(attributes, pattern, valueToDestruct, inValue) =>
      destructureCase(
        attributes,
        pattern,
        valueToDestruct.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        inValue.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        )
      )
    case Field(attributes, target, name) =>
      fieldCase(
        attributes,
        target.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        name
      )
    case FieldFunction(attributes, name) => fieldFunctionCase(attributes, name)
    case IfThenElse(attributes, condition, thenBranch, elseBranch) =>
      ifThenElseCase(
        attributes,
        condition.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        thenBranch.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        elseBranch.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        )
      )
    case Lambda(attributes, argumentPattern, body) =>
      lambdaCase(
        attributes,
        argumentPattern,
        body.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        )
      )
    case LetDefinition(attributes, valueName, valueDefinition, inValue) =>
      letDefinitionCase(
        attributes,
        valueName,
        valueDefinition.toCase.map(
          _.fold(
            applyCase,
            constructorCase,
            destructureCase,
            fieldCase,
            fieldFunctionCase,
            ifThenElseCase,
            lambdaCase,
            letDefinitionCase,
            letRecursionCase,
            listCase,
            literalCase,
            nativeApplyCase,
            patternMatchCase,
            recordCase,
            referenceCase,
            tupleCase,
            unitCase,
            updateRecordCase,
            variableCase
          )
        ),
        inValue.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        )
      )
    case LetRecursion(attributes, valueDefinitions, inValue) =>
      letRecursionCase(
        attributes,
        valueDefinitions.map { case (n, d) =>
          (
            n,
            d.toCase.map(
              _.fold(
                applyCase,
                constructorCase,
                destructureCase,
                fieldCase,
                fieldFunctionCase,
                ifThenElseCase,
                lambdaCase,
                letDefinitionCase,
                letRecursionCase,
                listCase,
                literalCase,
                nativeApplyCase,
                patternMatchCase,
                recordCase,
                referenceCase,
                tupleCase,
                unitCase,
                updateRecordCase,
                variableCase
              )
            )
          )
        },
        inValue.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        )
      )
    case ListType(attributes, elements) =>
      listCase(
        attributes,
        elements.map(
          _.fold(
            applyCase,
            constructorCase,
            destructureCase,
            fieldCase,
            fieldFunctionCase,
            ifThenElseCase,
            lambdaCase,
            letDefinitionCase,
            letRecursionCase,
            listCase,
            literalCase,
            nativeApplyCase,
            patternMatchCase,
            recordCase,
            referenceCase,
            tupleCase,
            unitCase,
            updateRecordCase,
            variableCase
          )
        )
      )
    case Literal(attributes, literal) => literalCase(attributes, literal)
    case NativeApply(attributes, function, arguments) =>
      nativeApplyCase(
        attributes,
        function,
        arguments.map(
          _.fold(
            applyCase,
            constructorCase,
            destructureCase,
            fieldCase,
            fieldFunctionCase,
            ifThenElseCase,
            lambdaCase,
            letDefinitionCase,
            letRecursionCase,
            listCase,
            literalCase,
            nativeApplyCase,
            patternMatchCase,
            recordCase,
            referenceCase,
            tupleCase,
            unitCase,
            updateRecordCase,
            variableCase
          )
        )
      )
    case PatternMatch(attributes, branchOutOn, cases) =>
      patternMatchCase(
        attributes,
        branchOutOn.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        cases.map { case (p, v) =>
          (
            p,
            v.fold(
              applyCase,
              constructorCase,
              destructureCase,
              fieldCase,
              fieldFunctionCase,
              ifThenElseCase,
              lambdaCase,
              letDefinitionCase,
              letRecursionCase,
              listCase,
              literalCase,
              nativeApplyCase,
              patternMatchCase,
              recordCase,
              referenceCase,
              tupleCase,
              unitCase,
              updateRecordCase,
              variableCase
            )
          )
        }
      )
    case Record(attributes, fields) =>
      recordCase(
        attributes,
        fields.map { case (n, v) =>
          (
            n,
            v.fold(
              applyCase,
              constructorCase,
              destructureCase,
              fieldCase,
              fieldFunctionCase,
              ifThenElseCase,
              lambdaCase,
              letDefinitionCase,
              letRecursionCase,
              listCase,
              literalCase,
              nativeApplyCase,
              patternMatchCase,
              recordCase,
              referenceCase,
              tupleCase,
              unitCase,
              updateRecordCase,
              variableCase
            )
          )
        }
      )
    case Reference(attributes, name) => referenceCase(attributes, name)
    case Tuple(attributes, elements) =>
      tupleCase(
        attributes,
        elements.map(
          _.fold(
            applyCase,
            constructorCase,
            destructureCase,
            fieldCase,
            fieldFunctionCase,
            ifThenElseCase,
            lambdaCase,
            letDefinitionCase,
            letRecursionCase,
            listCase,
            literalCase,
            nativeApplyCase,
            patternMatchCase,
            recordCase,
            referenceCase,
            tupleCase,
            unitCase,
            updateRecordCase,
            variableCase
          )
        )
      )
    case UnitType(attributes) => unitCase(attributes)
    case UpdateRecord(attributes, valueToUpdate, fieldsToUpdate) =>
      updateRecordCase(
        attributes,
        valueToUpdate.fold(
          applyCase,
          constructorCase,
          destructureCase,
          fieldCase,
          fieldFunctionCase,
          ifThenElseCase,
          lambdaCase,
          letDefinitionCase,
          letRecursionCase,
          listCase,
          literalCase,
          nativeApplyCase,
          patternMatchCase,
          recordCase,
          referenceCase,
          tupleCase,
          unitCase,
          updateRecordCase,
          variableCase
        ),
        fieldsToUpdate.map { case (n, v) =>
          (
            n,
            v.fold(
              applyCase,
              constructorCase,
              destructureCase,
              fieldCase,
              fieldFunctionCase,
              ifThenElseCase,
              lambdaCase,
              letDefinitionCase,
              letRecursionCase,
              listCase,
              literalCase,
              nativeApplyCase,
              patternMatchCase,
              recordCase,
              referenceCase,
              tupleCase,
              unitCase,
              updateRecordCase,
              variableCase
            )
          )
        }
      )
    case Variable(attributes, name) => variableCase(attributes, name)
  }

  def foldLeft[Z](initial: Z)(f: (Z, Value[TA, VA]) => Z): Z = {
    @tailrec
    def loop(stack: List[Value[TA, VA]], acc: Z): Z =
      stack match {
        case Nil                                   => acc
        case (t @ Apply(_, _, _)) :: tail          => loop(t.function :: t.arguments.toList ::: tail, f(acc, t))
        case (t @ Constructor(_, _)) :: tail       => loop(tail, f(acc, t))
        case (t @ Destructure(_, _, _, _)) :: tail => loop(t.valueToDestruct :: t.inValue :: tail, f(acc, t))
        case (t @ Field(_, _, _)) :: tail          => loop(t.target :: tail, f(acc, t))
        case (t @ FieldFunction(_, _)) :: tail     => loop(tail, f(acc, t))
        case (t @ IfThenElse(_, _, _, _)) :: tail =>
          loop(t.condition :: t.thenBranch :: t.elseBranch :: tail, f(acc, t))
        case (t @ Lambda(_, _, _)) :: tail           => loop(t.body :: tail, f(acc, t))
        case (t @ LetDefinition(_, _, _, _)) :: tail => loop(t.valueDefinition.body :: t.inValue :: tail, f(acc, t))
        case (t @ LetRecursion(_, _, _)) :: tail =>
          loop(t.valueDefinitions.map(_._2.body).toList ::: t.inValue :: tail, f(acc, t))
        case (t @ ListType(_, _)) :: tail        => loop(t.elements.toList ::: tail, f(acc, t))
        case (t @ Literal(_, _)) :: tail         => loop(tail, f(acc, t))
        case (t @ NativeApply(_, _, _)) :: tail  => loop(t.arguments.toList ::: tail, f(acc, t))
        case (t @ PatternMatch(_, _, _)) :: tail => loop(t.branchOutOn :: t.cases.map(_._2).toList ::: tail, f(acc, t))
        case (t @ Record(_, _)) :: tail          => loop(t.fields.map(_._2).toList ::: tail, f(acc, t))
        case (t @ Reference(_, _)) :: tail       => loop(tail, f(acc, t))
        case (t @ Tuple(_, _)) :: tail           => loop(t.elements.toList ::: tail, f(acc, t))
        case (t @ UnitType(_)) :: tail           => loop(tail, f(acc, t))
        case (t @ UpdateRecord(_, _, _)) :: tail =>
          loop(t.valueToUpdate :: t.fieldsToUpdate.map(_._2).toList ::: tail, f(acc, t))
        case (t @ Variable(_, _)) :: tail => loop(tail, f(acc, t))
      }

    loop(List(self), initial)
  }

}

object Value {

  final case class Apply[+TA, +VA](attributes: VA, function: Value[TA, VA], arguments: Chunk[Value[TA, VA]])
      extends Value[TA, VA]

  object Apply {
    type Raw = Apply[scala.Unit, scala.Unit]
    def apply(function: RawValue, arguments: Chunk[RawValue]): Raw =
      Apply((), function, arguments)
  }

  final case class Constructor[+VA](attributes: VA, name: FQName) extends Value[Nothing, VA]
  object Constructor {
    type Raw = Constructor[scala.Unit]
    def apply(name: FQName): Raw = Constructor((), name)
  }

  final case class Destructure[+TA, +VA](
      attributes: VA,
      pattern: Pattern[VA],
      valueToDestruct: Value[TA, VA],
      inValue: Value[TA, VA]
  ) extends Value[TA, VA]

  object Destructure {
    type Raw = Destructure[scala.Unit, scala.Unit]
    def apply(pattern: Pattern[scala.Unit], valueToDestruct: RawValue, inValue: RawValue): Raw =
      Destructure((), pattern, valueToDestruct, inValue)
  }

  final case class Field[+TA, +VA](attributes: VA, target: Value[TA, VA], name: Name) extends Value[TA, VA]

  object Field {
    type Raw = Field[scala.Unit, scala.Unit]
    def apply(target: RawValue, name: Name): Raw = Field((), target, name)
  }
  final case class FieldFunction[+VA](attributes: VA, name: Name) extends Value[Nothing, VA]

  object FieldFunction {
    type Raw = FieldFunction[scala.Unit]
    def apply(name: Name): Raw = FieldFunction((), name)
  }

  final case class IfThenElse[+TA, +VA](
      attributes: VA,
      condition: Value[TA, VA],
      thenBranch: Value[TA, VA],
      elseBranch: Value[TA, VA]
  ) extends Value[TA, VA]

  object IfThenElse {
    type Raw = IfThenElse[scala.Unit, scala.Unit]
    def apply(condition: RawValue, thenBranch: RawValue, elseBranch: RawValue): Raw =
      IfThenElse((), condition, thenBranch, elseBranch)
  }

  final case class Lambda[+TA, +VA](attributes: VA, argumentPattern: Pattern[VA], body: Value[TA, VA])
      extends Value[TA, VA]

  object Lambda {
    type Raw = Lambda[scala.Unit, scala.Unit]
    def apply(argumentPattern: Pattern[scala.Unit], body: RawValue): Raw = Lambda((), argumentPattern, body)
  }

  final case class LetDefinition[+TA, +VA](
      attributes: VA,
      valueName: Name,
      valueDefinition: Definition[TA, VA],
      inValue: Value[TA, VA]
  ) extends Value[TA, VA]

  object LetDefinition {
    type Raw = LetDefinition[scala.Unit, scala.Unit]
    def apply(valueName: Name, valueDefinition: Definition[scala.Unit, scala.Unit], inValue: RawValue): Raw =
      LetDefinition((), valueName, valueDefinition, inValue)
  }

  final case class LetRecursion[+TA, +VA](
      attributes: VA,
      valueDefinitions: Map[Name, Definition[TA, VA]],
      inValue: Value[TA, VA]
  ) extends Value[TA, VA]

  object LetRecursion {
    type Raw = LetRecursion[scala.Unit, scala.Unit]
    def apply(valueDefinitions: Map[Name, Definition[scala.Unit, scala.Unit]], inValue: RawValue): Raw =
      LetRecursion((), valueDefinitions, inValue)
  }

  final case class List[+TA, +VA](attributes: VA, elements: Chunk[Value[TA, VA]]) extends Value[TA, VA]

  object List {
    type Raw = List[scala.Unit, scala.Unit]
    def apply(elements: Chunk[RawValue]): Raw = List((), elements)
  }

  final case class Literal[+VA, +A](attributes: VA, literal: Lit[A]) extends Value[Nothing, VA]

  object Literal {
    type Raw[+A] = Literal[scala.Unit, A]
    def apply[A](literal: Lit[A]): Raw[A] = Literal((), literal)
  }

  final case class NativeApply[+TA, +VA](attributes: VA, function: NativeFunction, arguments: Chunk[Value[TA, VA]])
      extends Value[TA, VA]

  object NativeApply {
    type Raw = NativeApply[scala.Unit, scala.Unit]
    def apply(function: NativeFunction, arguments: Chunk[RawValue]): Raw =
      NativeApply((), function, arguments)
  }

  final case class PatternMatch[+TA, +VA](
      attributes: VA,
      branchOutOn: Value[TA, VA],
      cases: Chunk[(Pattern[VA], Value[TA, VA])]
  ) extends Value[TA, VA]

  object PatternMatch {
    type Raw = PatternMatch[scala.Unit, scala.Unit]
    def apply(branchOutOn: RawValue, cases: Chunk[(Pattern[scala.Unit], RawValue)]): Raw =
      PatternMatch((), branchOutOn, cases)
  }

  final case class Record[+TA, +VA](attributes: VA, fields: Chunk[(Name, Value[TA, VA])]) extends Value[TA, VA]

  object Record {
    type Raw = Record[scala.Unit, scala.Unit]
    def apply(fields: Chunk[(Name, RawValue)]): Raw = Record((), fields)
  }

  final case class Reference[+VA](attributes: VA, name: FQName) extends Value[Nothing, VA]

  object Reference {
    type Raw = Reference[scala.Unit]
    def apply(name: FQName): Raw = Reference((), name)
  }

  final case class Tuple[+TA, +VA](attributes: VA, elements: Chunk[Value[TA, VA]]) extends Value[TA, VA]

  object Tuple {
    type Raw = Tuple[scala.Unit, scala.Unit]
    def apply(elements: Chunk[RawValue]): Raw = Tuple((), elements)
  }

  final case class Unit[+VA](attributes: VA) extends Value[Nothing, VA]
  object Unit {
    type Raw = Unit[scala.Unit]
    def apply(): Raw = Unit(())
  }

  final case class UpdateRecord[+TA, +VA](
      attributes: VA,
      valueToUpdate: Value[TA, VA],
      fieldsToUpdate: Chunk[(Name, Value[TA, VA])]
  ) extends Value[TA, VA]

  object UpdateRecord {
    type Raw = UpdateRecord[scala.Unit, scala.Unit]
    def apply(valueToUpdate: RawValue, fieldsToUpdate: Chunk[(Name, RawValue)]): Raw =
      UpdateRecord((), valueToUpdate, fieldsToUpdate)
  }

  final case class Variable[+VA](attributes: VA, name: Name) extends Value[Nothing, VA]
  object Variable {
    type Raw = Variable[scala.Unit]
    def apply(name: Name): Raw = Variable((), name)
  }
}