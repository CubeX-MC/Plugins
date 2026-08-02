package org.cubexmc.metro.spatial

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * A thread-safe, generic octree for spatial indexing.
 */
class Octree<T> {
    private val boundary: Range3D
    private val maxDepth: Int
    private val maxItems: Int
    private val lock: ReadWriteLock = ReentrantReadWriteLock()
    private val items: MutableMap<Range3D, T> = ConcurrentHashMap()
    private var children: Array<Octree<T>>? = null
    private var depth: Int

    constructor(boundary: Range3D, maxDepth: Int, maxItems: Int) : this(boundary, maxDepth, maxItems, 0)

    private constructor(boundary: Range3D, maxDepth: Int, maxItems: Int, depth: Int) {
        this.boundary = boundary
        this.maxDepth = maxDepth
        this.maxItems = maxItems
        this.depth = depth
    }

    fun insert(range: Range3D, data: T): Boolean {
        lock.writeLock().lock()
        try {
            if (!boundary.intersects(range)) return false

            val currentChildren = children
            if (currentChildren != null) {
                for (child in currentChildren) {
                    if (child.boundary.intersects(range)) {
                        if (child.insert(range, data)) return true
                    }
                }
                return false
            }

            items[range] = data
            if (items.size > maxItems && depth < maxDepth) {
                subdivide()
            }
            return true
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun remove(range: Range3D): Boolean {
        lock.writeLock().lock()
        try {
            if (items.remove(range) != null) return true

            val currentChildren = children
            if (currentChildren != null) {
                for (child in currentChildren) {
                    if (child.boundary.intersects(range)) {
                        if (child.remove(range)) return true
                    }
                }
            }
            return false
        } finally {
            lock.writeLock().unlock()
        }
    }

    fun firstRange(point: Point3D): T? {
        lock.readLock().lock()
        try {
            if (!boundary.contains(point)) return null

            for ((range, data) in items) {
                if (range.contains(point)) return data
            }

            val currentChildren = children
            if (currentChildren != null) {
                for (child in currentChildren) {
                    if (child.boundary.contains(point)) {
                        val found = child.firstRange(point)
                        if (found != null) return found
                    }
                }
            }
            return null
        } finally {
            lock.readLock().unlock()
        }
    }

    fun getAllRanges(point: Point3D): List<T> {
        val results = ArrayList<T>()
        lock.readLock().lock()
        try {
            if (!boundary.contains(point)) return results

            for ((range, data) in items) {
                if (range.contains(point)) {
                    results.add(data)
                }
            }

            val currentChildren = children
            if (currentChildren != null) {
                for (child in currentChildren) {
                    if (child.boundary.contains(point)) {
                        results.addAll(child.getAllRanges(point))
                    }
                }
            }
            return results
        } finally {
            lock.readLock().unlock()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subdivide() {
        if (children != null || depth >= maxDepth) return
        val subRanges = boundary.subdivide()
        val newChildren = arrayOfNulls<Octree<T>>(8)
        for (i in 0 until 8) {
            newChildren[i] = Octree(subRanges[i], maxDepth, maxItems, depth + 1)
        }
        children = newChildren as Array<Octree<T>>

        val currentItems = HashMap(items)
        items.clear()
        val currentChildren = children ?: return
        for ((range, data) in currentItems) {
            var inserted = false
            for (child in currentChildren) {
                if (child.boundary.intersects(range)) {
                    if (child.insert(range, data)) {
                        inserted = true
                        break
                    }
                }
            }
            if (!inserted) items[range] = data
        }
    }

    fun clear() {
        lock.writeLock().lock()
        try {
            items.clear()
            val currentChildren = children
            if (currentChildren != null) {
                for (child in currentChildren) child.clear()
                children = null
            }
        } finally {
            lock.writeLock().unlock()
        }
    }
}
