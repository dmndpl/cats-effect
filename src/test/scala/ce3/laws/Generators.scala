/*
 * Copyright 2020 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ce3

import cats.Show
import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import cats.~>
import cats.Monad
import cats.Applicative
import cats.Functor
import cats.ApplicativeError
import scala.collection.immutable.SortedMap
import cats.MonadError

trait GenK[F[_]] {
  def apply[A: Arbitrary: Cogen]: Gen[F[A]]
}

// Generators for * -> * kinded types
trait Generators1[F[_]] {
  protected val maxDepth: Int = 10
  
  //todo: uniqueness based on... names, I guess. Have to solve the diamond problem somehow

  //Generators of base cases, with no recursion
  protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])]

  //Only recursive generators - the argument is a generator of the next level of depth
  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])]

  //All generators possible at depth [[depth]]
  private def gen[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    val genK: GenK[F] = new GenK[F] {
      def apply[B: Arbitrary: Cogen]: Gen[F[B]] = Gen.delay(gen(depth + 1))
    }
    
    val gens =
      if(depth > maxDepth) baseGen[A]
      else baseGen[A] ++ recursiveGen[A](genK) 

    Gen.oneOf(SortedMap(gens:_*).map(_._2)).flatMap(identity)
  }

  //All generators possible at depth 0 - the only public method
  def generators[A: Arbitrary: Cogen]: Gen[F[A]] = gen[A](0)
}

//Applicative is the first place that lets us introduce values in the context, if we discount InvariantMonoidal
trait ApplicativeGenerators[F[_]] extends Generators1[F] {
  implicit val F: Applicative[F]

  protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = List("pure" -> genPure[A])

  protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] =
    List(
      "map" -> genMap[A](deeper),
      "ap" -> genAp[A](deeper)
    )    

  private def genPure[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(_.pure[F])

  private def genMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = for {
    fa <- deeper[A]
    f <- Arbitrary.arbitrary[A => A]
  } yield F.map(fa)(f)

  private def genAp[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = for {
    fa <- deeper[A]
    ff <- deeper[A => A]
  } yield F.ap(ff)(fa)
}

trait MonadGenerators[F[_]] extends ApplicativeGenerators[F]{

  implicit val F: Monad[F]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "flatMap" -> genFlatMap(deeper)
  ) ++ super.recursiveGen(deeper)

  private def genFlatMap[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      fa <- deeper[A]
      f <- Gen.function1[A, F[A]](deeper[A])
    } yield fa.flatMap(f)
  }
}

trait ApplicativeErrorGenerators[F[_], E] extends ApplicativeGenerators[F] {
  implicit val arbitraryE: Arbitrary[E]
  implicit val cogenE: Cogen[E]

  implicit val F: ApplicativeError[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = List(
    "raiseError" -> genRaiseError[A]
  ) ++ super.baseGen[A]

  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "handleErrorWith" -> genHandleErrorWith[A](deeper),
  ) ++ super.recursiveGen(deeper)

  private def genRaiseError[A]: Gen[F[A]] =
    arbitrary[E].map(F.raiseError[A](_))

  private def genHandleErrorWith[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      fa <- deeper[A]
      f <- Gen.function1[E, F[A]](deeper[A])
    } yield F.handleErrorWith(fa)(f)
  }
}

trait MonadErrorGenerators[F[_], E] extends ApplicativeErrorGenerators[F, E] with MonadGenerators[F] {
  implicit val F: MonadError[F, E]
}

trait SafeGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Safe[F, E]
  type Case[_]
}

trait BracketGenerators[F[_], E] extends SafeGenerators[F, E] {
  implicit val F: Bracket[F, E]
  type Case[A] = F.Case[A]
  implicit def cogenCase[A: Cogen]: Cogen[Case[A]]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "bracketCase" -> genBracketCase[A](deeper)
  ) ++ super.recursiveGen[A](deeper)

  import OutcomeGenerators._

  private def genBracketCase[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] = {
    for {
      acquire <- deeper[A]
      use <- Gen.function1[A, F[A]](deeper[A])
      release <- Gen.function2[A, Case[A], F[Unit]](deeper[Unit])
    } yield F.bracketCase(acquire)(use)(release)
  }
}

trait RegionGenerators[R[_[_], _], F[_], E] extends SafeGenerators[R[F, *], E] {
  implicit val F: Region[R, F, E]
  type Case[A] = F.Case[A]

  def GenKF: GenK[F]


}

trait ConcurrentGenerators[F[_], E] extends MonadErrorGenerators[F, E] {
  implicit val F: Concurrent[F, E]

  override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[F[A]])] = List(
    "canceled" -> genCanceled[A],
    "cede" -> genCede[A],
    "never" -> genNever[A]
  ) ++ super.baseGen[A]
  
  override protected def recursiveGen[A: Arbitrary: Cogen](deeper: GenK[F]): List[(String, Gen[F[A]])] = List(
    "uncancelable" -> genUncancelable[A](deeper),
    "racePair" -> genRacePair[A](deeper),
    "start" -> genStart[A](deeper),
    "join" -> genJoin[A](deeper),
  ) ++ super.recursiveGen(deeper)

  private def genCanceled[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.canceled.as(_))

  private def genCede[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.cede.as(_))

  private def genNever[A]: Gen[F[A]] =
    F.never[A]

  // TODO we can't really use poll :-( since we can't Cogen FunctionK
  private def genUncancelable[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    deeper[A].map(pc => F.uncancelable(_ => pc))

  private def genStart[A: Arbitrary](deeper: GenK[F]): Gen[F[A]] =
    deeper[Unit].flatMap(pc => arbitrary[A].map(a => F.start(pc).as(a)))

  private def genJoin[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fiber <- deeper[A]
      cont <- deeper[Unit]
      a <- arbitrary[A]
    } yield F.start(fiber).flatMap(f => cont >> f.join).as(a)

  private def genRacePair[A: Arbitrary: Cogen](deeper: GenK[F]): Gen[F[A]] =
    for {
      fa <- deeper[A]
      fb <- deeper[A]

      cancel <- arbitrary[Boolean]

      back = F.racePair(fa, fb) flatMap {
        case Left((a, f)) =>
          if (cancel)
            f.cancel.as(a)
          else
            f.join.as(a)

        case Right((f, a)) =>
          if (cancel)
            f.cancel.as(a)
          else
            f.join.as(a)
      }
    } yield back
}


object OutcomeGenerators {
  def outcomeGenerators[F[_]: Applicative, E: Arbitrary: Cogen] = new ApplicativeErrorGenerators[Outcome[F, E, *], E] {
    val arbitraryE: Arbitrary[E] = implicitly
    val cogenE: Cogen[E] = implicitly
    implicit val F: ApplicativeError[Outcome[F, E, *], E] = Outcome.applicativeError[F, E]

    override protected def baseGen[A: Arbitrary: Cogen]: List[(String, Gen[Outcome[F,E,A]])] = List(
      "const(Canceled)" -> Gen.const(Outcome.Canceled)
    ) ++ super.baseGen[A]
  }
  
  implicit def arbitraryOutcome[F[_]: Applicative, E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Arbitrary[Outcome[F, E, A]] =
    Arbitrary {
      outcomeGenerators[F, E].generators[A]
    }

  implicit def cogenOutcome[F[_], E: Cogen, A](implicit A: Cogen[F[A]]): Cogen[Outcome[F, E, A]] = Cogen[Option[Either[E, F[A]]]].contramap {
    case Outcome.Canceled => None
    case Outcome.Completed(fa) => Some(Right(fa))
    case Outcome.Errored(e)    => Some(Left(e))
  }
}

object ResourceGenerators {

  def resourceGenerators[F[_], Case[_], E: Arbitrary: Cogen](
    implicit
    bracket: Bracket.Aux[F, E, Case],
    genKF: GenK[F]
  ) = new RegionGenerators[Resource, F, E] {
    val arbitraryE: Arbitrary[E] = implicitly[Arbitrary[E]]
    val cogenE: Cogen[E] = Cogen[E]
    
    val F: Region.Aux[Resource,F,E, Case] = Resource.regionForResource(bracket)
    val GenKF: GenK[F] = genKF
  }
  // def genResource[F[_]: Bracket.Aux[*[_], E, Case], Case[_], E, A: Arbitrary: Cogen](
  //     implicit arbEffect: Arbitrary[F[Resource[F, A]]],
  //     arbAlloate: Arbitrary[F[(A, Case[_] => F[Unit])]]
  // ): Gen[Resource[F, A]] = {
  //   val self = Gen.delay(genResource[F, Case, E, A])
  //   Gen.frequency(
  //     1 -> genPureResource[F, E, A],
  //     1 -> genSuspendResource[F, E, A],
  //     1 -> genApplyResource[F, Case, E, A],
  //     1 -> genFlatMapResource[F, E, A](
  //       self,
  //       Arbitrary.arbFunction1(Arbitrary(self), Cogen[A]).arbitrary
  //     )
  //   )
  // }

  // def genPureResource[F[_]: Bracket[*[_], E], E, A: Arbitrary]
  //     : Gen[Resource[F, A]] =
  //   Arbitrary.arbitrary[A].map(Resource.pure[F, A](_))

  // def genSuspendResource[F[_]: Bracket[*[_], E], E, A](
  //     implicit arbEffect: Arbitrary[F[Resource[F, A]]]
  // ): Gen[Resource[F, A]] =
  //   arbEffect.arbitrary.map(Resource.suspend)

  // def genApplyResource[F[_]: Bracket.Aux[*[_], E, Case], Case[_], E, A](
  //     implicit arbFA: Arbitrary[F[(A, Case[_] => F[Unit])]]
  // ): Gen[Resource[F, A]] =
  //   arbFA.arbitrary.map(Resource.applyCase[F, Case, E, A](_))

  // def genFlatMapResource[F[_]: Bracket[*[_], E], E, A](
  //     baseGen: Gen[Resource[F, A]],
  //     genFunction: Gen[A => Resource[F, A]]
  // ): Gen[Resource[F, A]] =
  //   for {
  //     base <- baseGen
  //     f <- genFunction
  //   } yield base.flatMap(f)

}
