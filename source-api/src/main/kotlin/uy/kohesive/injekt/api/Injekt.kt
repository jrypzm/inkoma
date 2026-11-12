package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

interface InjektFactory {
    fun <R : Any> getInstance(forType: Type): R
    fun <R : Any> getInstanceOrElse(forType: Type, default: R): R
    fun <R : Any> getInstanceOrNull(forType: Type): R?
}

interface InjektRegistry {
    fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T)
    fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R)
    fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R)
}

interface InjektRegistrar : InjektRegistry, InjektFactory

interface TypeReference<T> {
    val type: Type
}

abstract class FullTypeReference<T> protected constructor() : TypeReference<T> {
    override val type: Type = javaClass.genericSuperclass.let { superClass ->
        if (superClass is Class<*>) {
            throw IllegalArgumentException("Internal error: TypeReference constructed without actual type information")
        }
        (superClass as ParameterizedType).actualTypeArguments[0]
    }
}

class ClassTypeReference<T>(override val type: Type) : TypeReference<T>

inline fun <reified T : Any> fullType(): FullTypeReference<T> = object : FullTypeReference<T>() {}

inline fun <reified T : Any> typeRef(): FullTypeReference<T> = object : FullTypeReference<T>() {}

private fun Type.erasedKey(): Type = when (this) {
    is ParameterizedType -> rawType
    else -> this
}

open class InjektScope : InjektRegistrar {
    private val instances = ConcurrentHashMap<Type, Any>()
    private val singletonFactories = ConcurrentHashMap<Type, () -> Any>()
    private val factories = ConcurrentHashMap<Type, () -> Any>()

    override fun <T : Any> addSingleton(forType: TypeReference<T>, singleInstance: T) {
        instances[forType.type.erasedKey()] = singleInstance
    }

    override fun <R : Any> addSingletonFactory(forType: TypeReference<R>, factoryCalledOnce: () -> R) {
        singletonFactories[forType.type.erasedKey()] = factoryCalledOnce
    }

    override fun <R : Any> addFactory(forType: TypeReference<R>, factoryCalledEveryTime: () -> R) {
        factories[forType.type.erasedKey()] = factoryCalledEveryTime
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstanceOrNull(forType: Type): R? {
        val key = forType.erasedKey()
        instances[key]?.let { return it as R }
        singletonFactories[key]?.let { factory ->
            synchronized(this) {
                instances[key]?.let { return it as R }
                val created = factory()
                instances[key] = created
                singletonFactories.remove(key)
                return created as R
            }
        }
        factories[key]?.let { return it() as R }
        return null
    }

    override fun <R : Any> getInstance(forType: Type): R =
        getInstanceOrNull(forType) ?: throw InjektionException("No registered instance or factory for type $forType")

    override fun <R : Any> getInstanceOrElse(forType: Type, default: R): R = getInstanceOrNull(forType) ?: default

}

class InjektionException(message: String) : RuntimeException(message)

inline fun <reified T : Any> InjektFactory.get(forType: TypeReference<T>): T = getInstance(forType.type)

inline fun <reified T : Any> InjektFactory.get(): T = getInstance(fullType<T>().type)

inline fun <reified T : Any> InjektFactory.getOrNull(): T? = getInstanceOrNull(fullType<T>().type)

inline fun <reified T : Any> InjektRegistry.addSingleton(instance: T) = addSingleton(fullType<T>(), instance)

inline fun <reified T : Any> InjektRegistry.addSingletonFactory(noinline factory: () -> T) = addSingletonFactory(fullType<T>(), factory)
