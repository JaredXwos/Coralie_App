package com.jaredxwos.coralie.data.library

import android.net.Uri
import java.io.IOException

class DocumentAccessException(
    val sourceUri: Uri,
    val grantPresent: Boolean,
    cause: Throwable,
) : IOException("The selected HTML document is no longer accessible", cause)
