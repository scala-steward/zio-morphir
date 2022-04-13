package zio.morphir.ir.value.recursive

import zio.morphir.testing.MorphirBaseSpec
import zio.test.ZSpec
object RecursiveValueSpec extends MorphirBaseSpec {
  def spec: ZSpec[Environment, Any] = suite("Value Spec")(
    suite("Apply")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Constructor")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Destructure")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Field")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("FieldFunction")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("IfThenElse")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Lambda")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("LetDefinition")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("LetRecursion")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("List")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Literal")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("PatternMatch")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Record")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Reference")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Tuple")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Unit")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("UpdateRecord")(
      suite("Attributed")(),
      suite("Unattributed")()
    ),
    suite("Variable")(
      suite("Attributed")(),
      suite("Unattributed")()
    )
  )
}