package com.ing.baker.baas.dashboard

import cats.effect.IO
import cats.effect.concurrent.Ref
import com.ing.baker.baas.dashboard.BakeryStateCache.{CacheRecipeInstanceMetadata, CacheRecipeMetadata}
import com.ing.baker.baas.dashboard.BakerEventEncoders._
import com.ing.baker.runtime.scaladsl.{BakerEvent, EventReceived, EventRejected, InteractionCompleted, InteractionFailed, InteractionStarted, RecipeAdded, RecipeInstanceCreated}
import io.circe.Json
import io.circe.syntax._

object BakeryStateCache {

  case class CacheRecipeMetadata(recipeId: String, recipeName: String, knownProcesses: Int, created: Long)

  case class CacheRecipeInstanceMetadata(recipeId: String, recipeInstanceId: String, created: Long)

  def empty =
    BakeryStateCache(Map.empty)

  def emptyRef: IO[Ref[IO, BakeryStateCache]] =
    Ref.of[IO, BakeryStateCache](BakeryStateCache.empty)
}

case class BakeryStateCache(inner: Map[String, (RecipeAdded, Map[String, (RecipeInstanceCreated, List[Json])])]) {

  def handleEvent(event: BakerEvent): BakeryStateCache = {
    event match {
      case event: EventReceived =>
        recipeInstanceEvent(event.recipeId, event.recipeInstanceId, event.asJson)
      case event: EventRejected =>
        copy()
      // TODO MISSING RECIPE ID FROM THIS EVENT!!!
      //recipeInstanceEvent(event.recipeId, event.recipeInstanceId, event.asJson)
      case event: InteractionFailed =>
        recipeInstanceEvent(event.recipeId, event.recipeInstanceId, event.asJson)
      case event: InteractionStarted =>
        recipeInstanceEvent(event.recipeId, event.recipeInstanceId, event.asJson)
      case event: InteractionCompleted =>
        recipeInstanceEvent(event.recipeId, event.recipeInstanceId, event.asJson)
      case event: RecipeInstanceCreated =>
        recipeInstanceCreated(event)
      case event: RecipeAdded =>
        recipeAdded(event)
    }
  }

  def recipeAdded(event: RecipeAdded): BakeryStateCache =
    copy(inner = inner + (event.recipeId -> (event, Map.empty)))

  def recipeInstanceCreated(event: RecipeInstanceCreated): BakeryStateCache = {
    val (recipeAddedEvent, instancesMap) = inner(event.recipeId)
    val newInstance = event.recipeInstanceId -> (event, List(recipeAddedEvent.asJson, event.asJson))
    copy(inner = inner + (event.recipeId -> (recipeAddedEvent -> (instancesMap + newInstance))))
  }

  def recipeInstanceEvent(recipeId: String, recipeInstanceId: String, event: Json): BakeryStateCache = {
    val (recipeAddedEvent, instancesMap) = inner(recipeId)
    val (instanceCreated, events) = instancesMap(recipeInstanceId)
    val newInstance = recipeInstanceId -> (instanceCreated, events.:+(event))
    copy(inner = inner + (recipeId -> (recipeAddedEvent -> (instancesMap + newInstance))))
  }

  def listRecipes: List[CacheRecipeMetadata] =
    inner.map { case (recipeId, (recipeAddedEvent, instances)) =>
      CacheRecipeMetadata(recipeId, recipeAddedEvent.recipeName, instances.size, recipeAddedEvent.date)
    }.toList

  def listInstances(recipeId: String): List[CacheRecipeInstanceMetadata] =
    inner.get(recipeId) match {
      case None =>
        List.empty
      case Some((_, instances)) =>
        instances.map { case (recipeInstanceId, (recipeInstanceCreatedEvent, _)) =>
          CacheRecipeInstanceMetadata(recipeId, recipeInstanceId, recipeInstanceCreatedEvent.timeStamp)
        }.toList
    }

  def listEvents(recipeId: String, recipeInstanceId: String): List[Json] =
    inner
      .get(recipeId)
      .flatMap(_._2.get(recipeInstanceId))
      .map(_._2)
      .getOrElse(List.empty)

  def getRecipe(recipeId: String): Option[RecipeAdded] =
    inner
      .get(recipeId)
      .map(_._1)

  def getRecipeInstance(recipeId: String, recipeInstanceId: String): Option[(RecipeAdded, RecipeInstanceCreated)] =
    inner
      .get(recipeId)
      .flatMap { case (recipeAdded, instances) =>
        instances.get(recipeInstanceId).map(recipeAdded -> _._1)
      }
}