package com.hotel.tutorial.model

import java.sql.Date
import scala.util.Random

case class Reservation(guestUUID: String, hotelId: String, startDate: Date, endDate: Date, roomNumber: Int, confirmationCode: String){

  def intersect(another: Reservation) =
    this.hotelId == another.hotelId && this.roomNumber == another.roomNumber &&
      (startDate.compareTo(another.startDate) >= 0 && startDate.compareTo(another.endDate) <=0 ||
        another.startDate.compareTo(startDate) >= 0 && another.startDate.compareTo(endDate) <=0
      )

  override def equals(obj: Any) = obj match {
    case Reservation(_,_,_,_,_, `confirmationCode`) => true
    case _ => false
  }

  override def hashCode() = confirmationCode.hashCode

}

object Reservation {
  def make(guestUUID: String, hotelId: String, startDate: Date, endDate: Date, roomNumber: Int): Reservation = {

    val characters = ('A' to 'Z') ++ ('0' to '9')
    val nCharacters = characters.length
    val confirmationCode = (1 to 10).map(_ => characters(Random.nextInt(nCharacters))).mkString

    Reservation(guestUUID, hotelId, startDate, endDate, roomNumber, confirmationCode)
  }
}
