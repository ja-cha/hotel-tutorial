package com.hotel.tutorial.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.hotel.tutorial.model.{CancelReservation, ChangeReservation, Command, Generate, HotelProtocol, ManageHotel, RequestReservation, Reservation, ReservationAccepted, ReservationCancelled, ReservationChanged}

import java.sql.Date
import java.time.LocalDate
import java.util.UUID
import scala.util.Random


// in real life this agent will connect to a UI to receive all the commands, here we will generate them
object ReservationAgent {

  val DELETE_PROB = 0.05
  val CHANGE_PROB = 0.2
  val START_DATE = LocalDate.of(2023,1,1)

  def generateAndSend(hotels: Vector[ActorRef[Command]], state: Map[String, Reservation])(implicit replyTo: ActorRef[HotelProtocol]): Unit ={
    val probability = Random.nextDouble()
    if(probability <= DELETE_PROB && state.keys.nonEmpty){
      //generate cancellation
      val confNumbers =  state.keysIterator.toVector
      val confNumberIndex = Random.nextInt(confNumbers.size)
      val confNumber = confNumbers(confNumberIndex)
      val reservation = state(confNumber)
      val hotel = hotels.find(_.path.name == reservation.hotelId)
      hotel.foreach(_.tell(CancelReservation(confNumber, replyTo )))

    }else if( probability <= CHANGE_PROB && state.keys.nonEmpty){
      //generate reservation change
      val confNumbers =  state.keysIterator.toVector
      val confNumberIndex = Random.nextInt(confNumbers.size)
      val confNumber = confNumbers(confNumberIndex)
      val Reservation(guestUUID, hotelId, startDate, endDate, roomNumber, confirmationCode) = state(confNumber)
      val localS = startDate.toLocalDate
      val localE = endDate.toLocalDate

      //can change duration OR room number
      val isDurationChange = Random.nextBoolean()
      val newReservation =
        if(isDurationChange){
          val newLocalS = localS.plusDays(Random.nextInt(5) - 2) // between -s and +3 days
          val tentativeLocalE = localE.plusDays(Random.nextInt(5) - 2) // same, but interval may be degenerate
          // make end at least newStart + 1 day
          val newLocalE =
            if(tentativeLocalE.compareTo(newLocalS) <=0)
              newLocalS.plusDays(Random.nextInt(5)+1)
            else
              tentativeLocalE
          Reservation(guestUUID, hotelId, Date.valueOf(newLocalS), Date.valueOf(newLocalE), roomNumber, confirmationCode)
        }else{// room change
          val newRoomNumber = Random.nextInt(100)+1
          Reservation(guestUUID, hotelId, startDate, endDate, newRoomNumber, confirmationCode)
        }
      // now do the reservation change
      val Reservation(_, _, newStartDate, newEndDate, newRoomNumber, _) = newReservation
      val hotel = hotels.find(_.path.name == hotelId)
      //take both changes into account in a single cell
      hotel.foreach((_.tell(ChangeReservation(confirmationCode, newStartDate, newEndDate, newRoomNumber, replyTo))))

    }else{
      //new reservation
      val hotelIndex = Random.nextInt(hotels.size)
      val startDate = START_DATE.plusDays(Random.nextInt(365))
      val endDate = startDate.plusDays(Random.nextInt(14))
      val roomNumber = Random.nextInt(100) + 1
      val hotel = hotels(hotelIndex)
      hotel.tell(RequestReservation(UUID.randomUUID().toString, Date.valueOf(startDate), Date.valueOf(endDate), roomNumber, replyTo))
    }
  }

  // simulate a large influx of commands, eg. messages, to Hotel actors
 def active(hotels: Vector[ActorRef[Command]], state: Map[String, Reservation]): Behavior[HotelProtocol] =
   Behaviors.receive[HotelProtocol]{ (context, message) =>
     implicit val self: ActorRef[HotelProtocol] = context.self
     message match {
       case Generate(nCommands) =>
         (1 to nCommands).foreach(_ => generateAndSend(hotels, state))
         Behaviors.same
       case ManageHotel(hotel) =>
        context.log.info(s"Managing hotel ${hotel.path.name}")
        active(hotels :+ hotel, state)
       case ReservationAccepted(reservation) =>
         context.log.info(s"Reservation accepted ${reservation.confirmationCode}")
         active(hotels, state + (reservation.confirmationCode ->  reservation))
       case ReservationChanged(_, newReservation) =>
         context.log.info(s"Reservation changed ${newReservation.confirmationCode}")
         active(hotels, state + (newReservation.confirmationCode ->  newReservation))
       case ReservationCancelled(reservation) =>
         context.log.info(s"Reservation cancelled ${reservation.confirmationCode}")
         active(hotels, state - reservation.confirmationCode )

       case _ => Behaviors.same
     }

   }

 def apply(): Behavior[HotelProtocol] = active(Vector(), Map())
}
