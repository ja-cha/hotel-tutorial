package com.hotel.tutorial.actor

import akka.actor.typed.Behavior
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.hotel.tutorial.model
import com.hotel.tutorial.model.{CancelReservation, ChangeReservation, Command, CommandFailure, Event, RequestReservation, Reservation, ReservationAccepted, ReservationCancelled, ReservationChanged}

// primary persistent actor
object Hotel {

  case class ReservationsLedger(reservations: Set[Reservation])
  // a function, from the current ledger of the actor and the command (a.k.a the message),
  // that the actor has just received, to an Effect,
  // which will have the event that is persisted in the event store and the new ledger of the actor.
  def commandHandler(hotelId: String): (ReservationsLedger, Command) =>Effect[Event, ReservationsLedger] = (ledger, command)=>
    command match {
      case  RequestReservation(guestId, startDate, endDate, roomNumber, replyTo)=>
        val tentativeReservation = Reservation.make(guestId, hotelId, startDate, endDate, roomNumber)
        // check for conflicting reservation among existing reservations
        val conflictingReservation: Option[Reservation] = ledger.reservations.find(reservation => reservation.intersect(tentativeReservation))

        if(conflictingReservation.isEmpty){ // success, persist ReservationAccepted event and reply to the booking agent actor, eg. "manager"
           Effect
             .persist(ReservationAccepted(tentativeReservation))
             .thenReply(replyTo)(ledger => ReservationAccepted(tentativeReservation))

         }else{
           Effect
             .reply(replyTo)(CommandFailure("Reservation request rejected: conflict with another reservation"))
         }
      case ChangeReservation(confirmationCode, startDate, endDate, roomNumber, replyTo)=>
        //find our reservation by confirmation number
        val originalReservationFound = ledger.reservations.find(_.confirmationCode == confirmationCode)
        val updatedReservation =  originalReservationFound.map(originalReservation=>
          //for the updated reservation, use a copy of the existing one, with only startDate, endDate and roomNumber updated.
          originalReservation.copy(startDate = startDate, endDate = endDate, roomNumber = roomNumber)
        )
        // instantiate ReservationChanged with the old and updated reservations as constructor arguments
        val reservationChangedOption = originalReservationFound.zip(updatedReservation).map(ReservationChanged.tupled)

        val conflictingReservationOption = updatedReservation.flatMap{ tentativeReservation =>
          ledger.reservations.find(r=> r.confirmationCode != confirmationCode && r.intersect(tentativeReservation))
        }

        (reservationChangedOption, conflictingReservationOption) match {
          case (None, _) =>
            Effect.reply(replyTo)(CommandFailure(s"Cannot update reservation $confirmationCode: not found"))
          case (_,Some(_)) =>
            Effect.reply(replyTo)(CommandFailure(s"Cannot update reservation $confirmationCode: conflicting reservations"))
          case(Some(reservationUpdated), None) =>
            Effect.persist(reservationUpdated).thenReply(replyTo)(s=>reservationUpdated)
        }
        // -  not found, failure
        // - create new tentative reservation
        //-  check for conflict,
        //-  persist or fail

      case CancelReservation(confirmationCode, replyTo) =>
        val reservationFound = ledger.reservations.find(_.confirmationCode == confirmationCode)
        reservationFound match {
          case Some(reservation) =>
            Effect.persist(ReservationCancelled(reservation)).thenReply(replyTo)(s=>model.ReservationCancelled(reservation))
          case None =>
            Effect.reply(replyTo)(CommandFailure(s"Cannot cancel reservation $confirmationCode: not found"))
        }
    }
  // a function, from the ledger of the actor at that point,
  // the even that has just been persisted and the new ledger of the actor after persisting that event.
  def eventHandler(hotelId: String): (ReservationsLedger, Event) => ReservationsLedger = (ledger, event) =>
    event match {
      case ReservationAccepted(acceptedReservation) =>
      val updatedLedger = ledger.copy(reservations = ledger.reservations + acceptedReservation)
      println(s"ledger changed: $updatedLedger")
        updatedLedger
      case ReservationChanged(oldReservation, newReservation) =>
        val updatedLedger = ledger.copy(reservations = ledger.reservations - oldReservation + newReservation)
        println(s"ledger changed: $updatedLedger")
        updatedLedger
      case ReservationCancelled(reservation) =>
        val updatedLedger = ledger.copy(reservations = ledger.reservations - reservation)
        println(s"ledger changed: $updatedLedger")
        updatedLedger
    }

  def apply(hotelId: String): Behavior[Command] = {

    //the Command (a.k.a the message) it will receive, the Event it will persist, the ledger it will update internally
    EventSourcedBehavior[Command, Event, ReservationsLedger](
      persistenceId = PersistenceId.ofUniqueId(hotelId),
      emptyState = ReservationsLedger(Set()),
      commandHandler = commandHandler(hotelId),
      eventHandler = eventHandler(hotelId)
    )

  }
}
