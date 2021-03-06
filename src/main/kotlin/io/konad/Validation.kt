package io.konad

import io.konad.applicative.flatten
import io.konad.exceptions.EitherException
import io.konad.hkt.ApplicativeFunctorKind
import io.konad.hkt.FunctorKind
import io.konad.hkt.Kind
import io.konad.hkt.MonadKind

sealed class Validation<out A, out B>: ApplicativeFunctorKind<ValidationOf, B>, MonadKind<ValidationOf, B> {
    data class Success<out B>(val success: B): Validation<Nothing, B>()
    data class Fail<out A>(val fail: A, val prev: Fail<A>? = null): Validation<A, Nothing>(){

        fun failures(): Collection<A> = (prev
            ?.run { failures() }
            ?: emptyList()) +
            listOf(fail)

        fun <D> transform(fn: (A) -> D): Fail<D> =
            Fail(fail = fn(fail), prev = prev?.transform(fn))
    }

    companion object{
        fun <B> pure(value: B) = Success(value)
    }

    fun get(): B = when(this){
        is Fail -> throw EitherException(fail)
        is Success -> success
    }

    inline fun <C> map(fn: (B) -> C): Validation<A, C> =
        flatMap { Success(fn(it)) }

    fun <D> mapFail(fn: (A) -> D): Validation<D, B> = when(this){
        is Success -> this
        is Fail -> transform(fn)
    }

    fun <D> mapAllFailures(fn: (Collection<A>) -> D): Validation<D, B> = when(this){
        is Success -> this
        is Fail -> Fail(fn(failures()))
    }

    override fun <C> mapK(fn: (B) -> C): FunctorKind<ValidationOf, C> = map(fn)
    override fun <C> apMapK(fn: (B) -> C): ApplicativeFunctorKind<ValidationOf, C> = map(fn)
    override fun <C> flatMapK(fn: (B) -> MonadKind<ValidationOf, C>): MonadKind<ValidationOf, C> = flatMap { fn(it).validation }
    override fun <C> apK(liftedFn: FunctorKind<ValidationOf, (B) -> C>): ApplicativeFunctorKind<ValidationOf, C> = ap(liftedFn.validation)
}

open class ValidationOf
val <B> Kind<ValidationOf, B>.validation
    get() = this as Validation<*, B>

@Suppress("UNCHECKED_CAST")
fun <A, B> Kind<ValidationOf, B>.validation() =
    this as Validation<A, B>

inline fun <A, B, C> Validation<A, B>.flatMap(fn: (B) -> Validation<A, C>): Validation<A, C> = when(this){
    is Validation.Success -> fn(success)
    is Validation.Fail -> this
}

fun <A, B, C> Validation<A, B>.ap(liftedFn: Validation<A, (B) -> C>): Validation<A, C> = when(liftedFn){
    is Validation.Success -> map(liftedFn.success)
    is Validation.Fail -> when(this){
        is Validation.Success -> liftedFn
        is Validation.Fail -> Validation.Fail(fail, prev?.let { Validation.Fail(it.fail, liftedFn)} ?: liftedFn)
    }
}


fun <A, B> Validation<A, B>.ifFail(default: B): B = when(this){
    is Validation.Fail -> default
    is Validation.Success -> success
}

fun <A, B> Validation<A, B>.ifFail(errorHandler: (Collection<A>) -> B): B = when(this){
    is Validation.Fail -> errorHandler(failures())
    is Validation.Success -> success
}

fun <A, B> Collection<Validation<A, B>>.flatten(): Validation<A, Collection<B>> =
    flatten(Validation.Companion::pure)
    .validation()

fun <A, B> Result<B>.ifErrors(errorTransform: (Result.Errors) -> A): Validation<A, B> = when(this){
    is Result.Ok -> value.success()
    is Result.Errors -> errorTransform(this).fail()
}

fun <A, B> B?.ifNullValidation(errorTransform: () -> A): Validation<A, B> = this
    ?.run { this.success() }
    ?: errorTransform().fail()

fun <T> Result<T>.toValidation(): Validation<Error, T> = when(this){
    is Result.Ok -> value.success()
    is Result.Errors -> toFail(this)
}

private fun toFail(errors: Result.Errors): Validation.Fail<Error> =
    Validation.Fail(fail = errors.error, prev = errors.prev?.run(::toFail))

fun <B> B.success(): Validation.Success<B> = Validation.Success(this)
fun <A> A.fail(): Validation.Fail<A> = Validation.Fail(this)

