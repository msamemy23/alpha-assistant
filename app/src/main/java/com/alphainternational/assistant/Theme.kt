package com.alphainternational.assistant

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Black canvas with soft raised surfaces, one calm mint accent, and purple only while a bot is working. Dark is the default look.
// The owner can also pick Light, or follow the phone (Settings → Look). Every colour below is a "get()" so one switch repaints the whole app:
// anything drawn with these names changes together. A colour should come from here, not from a hex written in a screen.

/** Which colours are on. Dark is the default and what the owner has today; Light uses the same names with its own values. */
object Palette {
    /** True while the light colours are in use. Colours read it, so changing it repaints every screen that uses them. */
    var light by mutableStateOf(false)
}
private fun pick(dark:Long,light:Long)=Color(if(Palette.light) light else dark)

val Mint:Color get()=pick(0xFFF2F2F2,0xFF1E2327)
val Background:Color get()=pick(0xFF000000,0xFFFAFAF8)
val Panel:Color get()=pick(0xFF111316,0xFFF0F1F2)
val Raised:Color get()=pick(0xFF171A1E,0xFFE8EAEC)
val Raised2:Color get()=pick(0xFF1F2328,0xFFE1E4E7)
/** A step above Raised2: pop-up menus, the emoji row. */
val Raised3:Color get()=pick(0xFF2C2C2E,0xFFD9DDE1)
val Muted:Color get()=pick(0xFF8C949C,0xFF59626A)
val Ink:Color get()=pick(0xFFECEFF1,0xFF14181C)
val Line:Color get()=pick(0xFF23272C,0xFFD5D9DD)
/** A stronger outline than Line: chips and buttons you can tap. */
val Outline:Color get()=pick(0xFF3A3A3C,0xFFBFC4C9)
val Purple:Color get()=pick(0xFFA78BFA,0xFF6542D8)
val PurpleDim:Color get()=pick(0xFF1E1830,0xFFEAE4FB)
val Amber:Color get()=pick(0xFFF6CF87,0xFF9A6200)
/** Text on an Amber badge. */
val OnAmber:Color get()=pick(0xFF000000,0xFFFFFFFF)
/** The one accent: the colour of the Alpha face, used for links, source marks and the main buttons in an answer. */
val Accent:Color get()=pick(0xFF9FE3CD,0xFF0B7A5E)
val AccentDim:Color get()=pick(0xFF16302A,0xFFDDF1E9)
/** Text and icons on top of Accent. */
val OnAccent:Color get()=pick(0xFF000000,0xFFFFFFFF)
/** The main button: white with black words in the dark look, near-black with white words in the light look. */
val Strong:Color get()=pick(0xFFFFFFFF,0xFF14181C)
val OnStrong:Color get()=pick(0xFF000000,0xFFFFFFFF)
/** The box you type in. */
val FieldBg:Color get()=pick(0xFF1A1A1A,0xFFECEEF0)
/** Dialogs and sheets. */
val SheetBg:Color get()=pick(0xFF161616,0xFFFFFFFF)
/** Something that stops or ends (end call). */
val Danger:Color get()=pick(0xFFDB615F,0xFFC0392B)
/** Green and red for "this works" and "this needs fixing" (always paired with words, never colour alone). */
val Good:Color get()=pick(0xFF5BD68A,0xFF1B7F46)
val Bad:Color get()=pick(0xFFFF7A70,0xFFC62828)

/**
 * The letters. Inter for everything you read (a clean, highly legible text face), Source Serif for the headline of an answer (it gives the
 * answer a voice, like a good article), JetBrains Mono for code. All three are open-licence fonts bundled in the app, so nothing is downloaded.
 */
@OptIn(ExperimentalTextApi::class)
private fun variable(res:Int,weight:Int,italic:Boolean=false)=Font(res,FontWeight(weight),if(italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings=FontVariation.Settings(FontVariation.weight(weight)))
val TextFace=FontFamily(variable(R.font.inter,400),variable(R.font.inter,500),variable(R.font.inter,600),variable(R.font.inter,700))
val SerifFace=FontFamily(variable(R.font.source_serif,400),variable(R.font.source_serif,500),variable(R.font.source_serif,600),variable(R.font.source_serif,700),
    variable(R.font.source_serif_italic,400,true),variable(R.font.source_serif_italic,600,true))
val CodeFace=FontFamily(variable(R.font.jetbrains_mono,400),variable(R.font.jetbrains_mono,500),variable(R.font.jetbrains_mono,700))

/**
 * Named text styles for answers and screens, so every reply in every chat reads the same way. They are "get()" so their colour follows the look
 * the owner chose (a style built once would keep the dark colour forever).
 */
object Type {
    /** The headline of an answer: one sentence that is the answer itself. */
    val answer:TextStyle get()=TextStyle(fontFamily=SerifFace,fontWeight=FontWeight.SemiBold,fontSize=23.sp,lineHeight=29.sp,letterSpacing=(-0.2).sp,color=Ink)
    val section:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=23.sp,letterSpacing=(-0.1).sp,color=Ink)
    val sub:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.SemiBold,fontSize=15.sp,lineHeight=21.sp,color=Ink)
    val body:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Normal,fontSize=16.sp,lineHeight=25.sp,color=Ink)
    val small:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Normal,fontSize=13.sp,lineHeight=19.sp,color=Muted)
    val label:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.SemiBold,fontSize=11.sp,lineHeight=14.sp,letterSpacing=1.1.sp,color=Muted)
    val mono:TextStyle get()=TextStyle(fontFamily=CodeFace,fontWeight=FontWeight.Normal,fontSize=13.sp,lineHeight=19.sp,color=Ink)
    val display:TextStyle get()=TextStyle(fontFamily=SerifFace,fontWeight=FontWeight.SemiBold,fontSize=36.sp,lineHeight=42.sp,letterSpacing=(-0.4).sp,color=Ink)
    /** A row's name in a list (a bot, a setting). */
    val row:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.SemiBold,fontSize=17.sp,lineHeight=22.sp,color=Ink)
    /** A row's second line. */
    val note:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Normal,fontSize=14.sp,lineHeight=20.sp,color=Muted)
    /** The words on a suggestion chip or a quick reply. */
    val chip:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Medium,fontSize=14.sp,lineHeight=19.sp,color=Ink)
    /** The smallest text that is still allowed on screen (times, counters). */
    val tiny:TextStyle get()=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Normal,fontSize=12.sp,lineHeight=16.sp,color=Muted)
}

/** What the owner picked in Settings → Look: "dark" (the default), "light" or "system" (follow the phone). */
fun lookIsLight(appearance:String,phoneIsDark:Boolean)=when(appearance) {"light"->true;"system"->!phoneIsDark;else->false}

/** [look] overrides the saved choice ("dark", "light" or "system"); the look tests use it so they never change the owner's setting. */
@Composable fun AlphaTheme(look:String?=null,content:@Composable ()->Unit) {
    val light=lookIsLight(look?:UiPrefs.appearance,isSystemInDarkTheme())
    // Set before anything below reads a colour, so the first frame already has the right look.
    remember(light) {Palette.light=light;light}
    val view=LocalView.current
    if(!view.isInEditMode) SideEffect {
        val window=(view.context as? Activity)?.window
        if(window!=null) {
            @Suppress("DEPRECATION") run {window.statusBarColor=Background.toArgb();window.navigationBarColor=Background.toArgb()}
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Background.toArgb()))
            WindowCompat.getInsetsController(window,view).apply {isAppearanceLightStatusBars=light;isAppearanceLightNavigationBars=light}
        }
    }
    val typography=Typography(
        displaySmall=TextStyle(fontFamily=SerifFace,fontWeight=FontWeight.SemiBold,fontSize=36.sp,lineHeight=42.sp,letterSpacing=(-0.4).sp),
        headlineMedium=TextStyle(fontFamily=SerifFace,fontWeight=FontWeight.SemiBold,fontSize=26.sp,lineHeight=32.sp,letterSpacing=(-0.2).sp),
        titleLarge=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.SemiBold,fontSize=20.sp,lineHeight=26.sp,letterSpacing=(-0.2).sp),
        titleMedium=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=22.sp),
        bodyLarge=TextStyle(fontFamily=TextFace,fontSize=16.sp,lineHeight=24.sp),
        bodyMedium=TextStyle(fontFamily=TextFace,fontSize=14.sp,lineHeight=21.sp),
        bodySmall=TextStyle(fontFamily=TextFace,fontSize=12.sp,lineHeight=17.sp),
        labelLarge=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Medium,fontSize=14.sp),
        labelMedium=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Medium,fontSize=12.sp),
        labelSmall=TextStyle(fontFamily=TextFace,fontWeight=FontWeight.Medium,fontSize=11.sp)
    )
    val scheme=if(light) lightColorScheme(primary=Strong,onPrimary=OnStrong,secondary=Accent,onSecondary=OnAccent,
            secondaryContainer=Raised2,onSecondaryContainer=Ink,background=Background,surface=Background,surfaceContainer=Panel,surfaceContainerHigh=Raised,
            surfaceVariant=Panel,onBackground=Ink,onSurface=Ink,onSurfaceVariant=Muted,outline=Outline,outlineVariant=Line)
        else darkColorScheme(primary=Strong,onPrimary=OnStrong,secondary=Accent,onSecondary=OnAccent,
            secondaryContainer=Raised2,onSecondaryContainer=Ink,background=Background,surface=Background,surfaceContainer=Panel,surfaceContainerHigh=Raised,
            surfaceVariant=Panel,onBackground=Ink,onSurface=Ink,onSurfaceVariant=Muted,outline=Color(0xFF3A3F45),outlineVariant=Line)
    MaterialTheme(colorScheme=scheme,typography=typography,content=content)
}
