package com.example.coupontracker.util

import android.content.Context
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.coupontracker.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageLoader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val requestOptions = RequestOptions()
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .centerCrop()
        .placeholder(R.drawable.ic_image_placeholder)
        .error(R.drawable.ic_image_error)

    fun loadImage(imageView: ImageView, uri: String?) {
        if (uri.isNullOrEmpty()) {
            imageView.setImageResource(R.drawable.ic_image_placeholder)
            return
        }

        Glide.with(context)
            .load(uri)
            .apply(requestOptions)
            .into(imageView)
    }

    fun loadImage(imageView: ImageView, uri: Uri?) {
        if (uri == null) {
            imageView.setImageResource(R.drawable.ic_image_placeholder)
            return
        }

        Glide.with(context)
            .load(uri)
            .apply(requestOptions)
            .into(imageView)
    }
} 