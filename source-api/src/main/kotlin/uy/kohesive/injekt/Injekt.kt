@file:JvmName("InjektKt")

package uy.kohesive.injekt

import uy.kohesive.injekt.api.InjektScope

/** Global scope, binary-compatible with `uy.kohesive.injekt.InjektKt.getInjekt()`. */
@Volatile
var Injekt: InjektScope = InjektScope()
