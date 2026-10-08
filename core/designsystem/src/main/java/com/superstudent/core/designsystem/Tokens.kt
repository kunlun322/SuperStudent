package com.superstudent.core.designsystem

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Design tokens per design §9.4
object SsColors {
    val Background = Color(0xFFF4FAF2)
    val Primary = Color(0xFF176B45)
    val PrimaryContainer = Color(0xFFDDF3E5)
    val Accent = Color(0xFFF39A3E)
    val Surface = Color(0xFFFFFFFF)
    val Error = Color(0xFFB3261E)
}

object SsShapes {
    val Card = RoundedCornerShape(24.dp)
    val Sheet = RoundedCornerShape(28.dp)
    val Input = RoundedCornerShape(16.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

object SsSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
    val MinTouch = 48.dp
}

object SsType {
    val Title = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = SsColors.Primary)
    val Section = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF123524))
    val Body = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, color = Color(0xFF243B2E))
    val Label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4A6154))
    val BodySmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = Color(0xFF4A6154))
    val Caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Color(0xFF6B7D72))
}

@Composable
fun SsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = SsShapes.Card,
        colors = CardDefaults.cardColors(containerColor = SsColors.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Surface(color = SsColors.Surface, shape = SsShapes.Card) {
            content()
        }
    }
}
