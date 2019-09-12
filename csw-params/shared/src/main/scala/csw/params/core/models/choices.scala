package csw.params.core.models

import java.util

import scala.annotation.varargs
import scala.jdk.CollectionConverters._
import scala.language.implicitConversions

/**
 * Represents a single choice
 *
 * @param name of the choice
 */
case class Choice(name: String) {
  override def toString: String = name
}

/**
 * Provides implicit conversion from String to Choice
 */
object Choice {

  /**
   * Create a Choice from given name
   *
   * @param name represents the name of the Choice
   * @return an instance of Choice
   */
  implicit def toChoice(name: String): Choice = new Choice(name)
}

/**
 * Represents a set of choices
 *
 * @param values a Set of Choice
 */
case class Choices(values: Set[Choice]) {

  /**
   * A helper method to determine if the provided choice is present in the set of choices this
   * instance holds
   *
   * @param choice the choice value to find
   * @return a Boolean indicating whether the given choice is present or not
   */
  def contains(choice: Choice): Boolean = values.contains(choice)

  /**
   * A comma separated string representation of all choices this instance holds
   */
  override def toString: String = values.mkString("(", ",", ")")

  /**
   * A Java helper to get all choices this instance holds
   *
   * @return
   */
  def jValues(): util.List[Choice] = values.toList.asJava
}

/**
 * Provides a varargs constructor for Choices
 */
object Choices {

  /**
   * Creates Choices from provided String values
   *
   * @param choices one or more choices in string format
   * @return an instance of Choices
   */
  @varargs
  def from(choices: String*): Choices               = Choices(choices.map(Choice(_)).toSet)
  def from(choices: java.util.Set[String]): Choices = from(choices.asScala.toSeq: _*)

  /**
   * Creates Choices from provided values
   *
   * @param choices one or more choices
   * @return an instance of Choices
   */
  @varargs
  def fromChoices(choices: Choice*): Choices               = Choices(choices.toSet)
  def fromChoices(choices: java.util.Set[Choice]): Choices = fromChoices(choices.asScala.toSeq: _*)
}
