package zio.morphir.ir

package object value {

  type RawValue = Value[Any, Any]
  val RawValue: Value.type = Value

  type TypedValue = Value[Any, UType]
  val TypedValue: Value.type = Value

  type UDefinition = Definition[Any, Any]
  val UDefinition: Definition.type = Definition

  type UPattern = Pattern[Any]
  val UPattern: Pattern.type = Pattern

  type USpecification = Value[Any, Any]
  val USpecification: Specification.type = Specification

}
