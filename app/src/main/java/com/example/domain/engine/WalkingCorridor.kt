package com.example.domain.engine

import com.example.domain.model.NormalizedRect

object WalkingCorridor {
    // Configurable trapezoidal corridor constants
    const val TOP_WIDTH = 0.40f
    const val BOTTOM_WIDTH = 0.80f
    const val VERTICAL_START = 0.25f
    const val VERTICAL_END = 1.0f
    
    const val OVERLAP_THRESHOLD = 0.15f // 15% overlap required

    fun isDetectionInCorridor(rect: NormalizedRect): Boolean {
        val yStart = maxOf(rect.top, VERTICAL_START)
        val yEnd = minOf(rect.bottom, VERTICAL_END)
        
        if (yStart >= yEnd) return false
        
        val steps = 10
        val dy = (yEnd - yStart) / steps
        var intersectionArea = 0f
        
        for (i in 0 until steps) {
            val y = yStart + dy * (i + 0.5f)
            val progress = (y - VERTICAL_START) / (VERTICAL_END - VERTICAL_START)
            val widthAtY = TOP_WIDTH + (BOTTOM_WIDTH - TOP_WIDTH) * progress
            val minX = 0.5f - widthAtY / 2f
            val maxX = 0.5f + widthAtY / 2f
            
            val overlapLeft = maxOf(rect.left, minX)
            val overlapRight = minOf(rect.right, maxX)
            
            if (overlapRight > overlapLeft) {
                intersectionArea += (overlapRight - overlapLeft) * dy
            }
        }
        
        return (intersectionArea / rect.area) >= OVERLAP_THRESHOLD
    }
}
