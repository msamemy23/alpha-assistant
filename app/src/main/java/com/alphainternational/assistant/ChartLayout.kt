package com.alphainternational.assistant

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The layout rules of bar and line charts, kept free of drawing so they can be checked off the phone.
 * What went wrong on the live phone (2026-10-11, "24 months of the S&P 500 as a line chart"): the chart kept only the first 12 of 24 points without saying so, its
 * axis started at 0 so the line was a flat stripe near the top, and the value labels were printed over one another.
 */
object ChartLayout {
    /** A pie has this many slices at most (more are unreadable), a bar or line chart this many points (monthly for five years). */
    const val MAX_SLICES=12
    const val MAX_POINTS=60

    /** The most points a chart of this type may have. */
    fun limit(type:String)=if(type=="pie") MAX_SLICES else MAX_POINTS

    /** Said to the bot when its data is too long, instead of silently dropping the rest. */
    fun tooMany(type:String,count:Int)=
        "This ${if(type=="pie") "pie" else type+" chart"} has $count points; the limit is ${limit(type)}${if(type=="pie") " slices" else ""}. Give fewer (for example monthly instead of daily, or the biggest ones and the rest as \"Other\")."

    /** The step between axis lines: 1, 2, 2.5 or 5 times a power of ten, so the labels read as round numbers (5,500, 6,000) and not as 5,016.35. */
    fun niceStep(rough:Double):Double {
        if(rough<=0 || rough.isNaN()) return 1.0
        val power=10.0.pow(floor(log10(rough)))
        val fraction=rough/power
        val nice=when {fraction<=1.0->1.0;fraction<=2.0->2.0;fraction<=2.5->2.5;fraction<=5.0->5.0;else->10.0}
        return nice*power
    }

    /**
     * The values the axis shows, lowest first. Bars always start at zero (their length is the number). A line follows its own data with a margin, because
     * a level that moves between 5,700 and 7,700 is a flat stripe on an axis that starts at 0; but data that is never negative never goes below 0.
     */
    fun axis(type:String,values:List<Double>,lines:Int=4):List<Double> {
        if(values.isEmpty()) return listOf(0.0,1.0)
        var low=values.min();var high=values.max()
        if(type=="line") {
            if(high==low) {val pad=abs(high).takeIf {it>0}?.times(0.05)?:1.0;low-=pad;high+=pad}
            else {val pad=(high-low)*0.08;low-=pad;high+=pad}
            if(values.min()>=0 && low<0) low=0.0
        } else {low=minOf(low,0.0);high=maxOf(high,0.0)}
        if(high==low) high=low+1.0
        val step=niceStep((high-low)/lines)
        val first=floor(low/step+1e-9)*step;val last=ceil(high/step-1e-9)*step
        val count=Math.round((last-first)/step).toInt().coerceIn(1,40)
        return (0..count).map {Math.round((first+it*step)*1e6)/1e6}
    }

    /**
     * Which of the items get a label when they are drawn side by side: [centers] are their middles and [widths] how wide each label is. The [first]
     * ones (listed in order of importance: the first and last point, the highest and the lowest) are placed first; then every other one that does not
     * touch a label already placed, from left to right. No two labels overlap, and the ones that matter are always there.
     */
    fun labelled(centers:List<Float>,widths:List<Float>,first:List<Int> = emptyList(),gap:Float=3f,allowed:(Int)->Boolean={true}):Set<Int> {
        val chosen=ArrayList<Int>()
        fun free(i:Int)=chosen.none {j->abs(centers[i]-centers[j])<(widths[i]+widths[j])/2f+gap}
        for(i in first.distinct()) if(i in centers.indices && free(i)) chosen+=i
        for(i in centers.indices) if(i !in chosen && allowed(i) && free(i)) chosen+=i
        return chosen.toSet()
    }

    /**
     * Where a line chart's number sits next to its point so the line never runs through it: [shift] -1 puts it to the left of the point (the line is rising
     * through the point, so that side is clear), +1 to the right (falling), 0 centred; [below] puts it under the point (a dip, or a line that leaves it upward).
     */
    data class Place(val shift:Int,val below:Boolean)
    fun place(values:List<Double>,i:Int):Place {
        val v=values[i];val before=values.getOrNull(i-1);val after=values.getOrNull(i+1)
        return when {
            before==null && after==null -> Place(0,false)
            before==null -> Place(0,after!!>v)
            after==null -> Place(0,before>v)
            before<v && after>v -> Place(-1,false)
            before>v && after<v -> Place(1,false)
            before>=v && after>=v && (before>v || after>v) -> Place(0,true)
            else -> Place(0,false)
        }
    }

    /** The items worth a value label before any others: the first and last, then the highest and the lowest. */
    fun important(values:List<Double>):List<Int> {
        if(values.isEmpty()) return emptyList()
        return listOf(0,values.lastIndex,values.indices.maxByOrNull {values[it]}!!,values.indices.minByOrNull {values[it]}!!).distinct()
    }
}
