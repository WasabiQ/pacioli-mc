package wasabi.paciolimc.api

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.boss.enderdragon.EnderDragon
import wasabi.paciolimc.logger.PacioliLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Predicate

object PacioliAPI {

    private val externalBossPredicates = CopyOnWriteArrayList<Predicate<Entity>>()
    private val explicitBossIds = ConcurrentHashMap.newKeySet<Int>()
    
    // 🏛️ The Hybrid Cache: Stores Registry IDs (e.g., "minecraft:wither")
    private val knownBossTypes = ConcurrentHashMap.newKeySet<String>()

    fun registerBossPredicate(predicate: Predicate<Entity>) {
        if (externalBossPredicates.size > 16) {
            PacioliLog.warn("API", "High predicate count. Consider using markAsBoss() for instances.")
        }
        externalBossPredicates.add(predicate)
    }

    fun markAsBoss(entity: Entity) = explicitBossIds.add(entity.id)
    fun unmarkAsBoss(entity: Entity) = explicitBossIds.remove(entity.id)
    fun purgeId(entityId: Int) = explicitBossIds.remove(entityId)

    /**
     * Unified Check: The "Filter Funnel" (Hybrid Cache Optimized)
     */
    fun isBoss(entity: Entity): Boolean {
        if (entity.isRemoved) return false
        
        val id = entity.id // Micro-opt: Store ID once
        
        // 1. Instance Lookup (Fastest - O(1))
        if (explicitBossIds.contains(id)) return true

        // 2. Type-Level Cache (Fast - O(1))
        // Registry name is stable and namespaced.
        val typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString()
        if (knownBossTypes.contains(typeKey)) return true

        // 3. Vanilla Hardcoded -> Promote to Cache
        if (entity is EnderDragon || entity is WitherBoss) {
            knownBossTypes.add(typeKey)
            return true
        }

        // 4. API Predicates (The Discovery Phase)
        for (predicate in externalBossPredicates) {
            try {
                if (predicate.test(entity)) {
                    // 💡 HYBRID WIN: Once a type passes a predicate, cache it globally.
                    // This turns O(N*P) into O(1) for all subsequent entities of this type.
                    knownBossTypes.add(typeKey)
                    return true
                }
            } catch (e: Exception) {
                PacioliLog.error("API", "Predicate crashed. Removing.", e)
                externalBossPredicates.remove(predicate) 
            }
        }

        // 5. Tags (Fallback - Lowest Priority)
        if (entity.commandTags.contains("pacioli:boss")) return true

        return false
    }
}