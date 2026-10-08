package com.superstudent.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun container(): AppContainer {
    val context = LocalContext.current.applicationContext
    return (context as SsApplication).container
}

inline fun <reified VM : ViewModel> vmFactory(crossinline create: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
    }

@Composable
inline fun <reified VM : ViewModel> ssViewModel(noinline create: (AppContainer) -> VM): VM {
    val appContainer = container()
    return viewModel<VM>(factory = vmFactory { create(appContainer) })
}

val Context.appContainer: AppContainer
    get() = (applicationContext as SsApplication).container
