@file:JvmName("InjektLazyKt")

package uy.kohesive.injekt

import uy.kohesive.injekt.api.fullType

inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.getInstance(fullType<T>().type) }

inline fun <reified T : Any> injectValue(): Lazy<T> = lazyOf(Injekt.getInstance(fullType<T>().type))
