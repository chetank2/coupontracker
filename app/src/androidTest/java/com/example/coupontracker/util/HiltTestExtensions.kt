package com.example.coupontracker.util

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StyleRes
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.example.coupontracker.HiltTestActivity
import com.example.coupontracker.R

@PublishedApi
internal const val THEME_EXTRAS_BUNDLE_KEY =
    "androidx.fragment.app.testing.FragmentScenario.EmptyFragmentActivity.THEME_EXTRAS_BUNDLE_KEY"

inline fun <reified T : Fragment> launchFragmentInHiltContainer(
    fragmentArgs: Bundle? = null,
    @StyleRes themeResId: Int = R.style.Theme_CouponTracker,
    crossinline action: T.(NavController) -> Unit = {}
) {
    val startActivityIntent = Intent(ApplicationProvider.getApplicationContext(), HiltTestActivity::class.java).apply {
        putExtra(THEME_EXTRAS_BUNDLE_KEY, themeResId)
    }

    ActivityScenario.launch<HiltTestActivity>(startActivityIntent).onActivity { activity ->
        val fragment = activity.supportFragmentManager.fragmentFactory.instantiate(
            T::class.java.classLoader!!,
            T::class.java.name
        )
        fragment.arguments = fragmentArgs

        activity.supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, fragment)
            .commitNow()

        val navController = androidx.navigation.testing.TestNavHostController(activity)
        navController.setGraph(R.navigation.nav_graph)
        fragment.view?.let { view ->
            androidx.navigation.Navigation.setViewNavController(view, navController)
        }

        @Suppress("UNCHECKED_CAST")
        action(fragment as T, navController)
    }
}
