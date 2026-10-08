package com.superstudent.app

object Routes {
    const val LOGIN = "login"
    const val SHELL = "shell?tab={tab}"
    const val PACKAGE_NEW = "package/new"
    const val PACKAGE_DETAIL = "package/{packageId}"
    const val RESULTS = "results/{packageId}"

    fun shell(tab: String) = "shell?tab=$tab"
    fun detail(packageId: String) = "package/$packageId"
    fun results(packageId: String) = "results/$packageId"
}
