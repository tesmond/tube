package com.tube.tv.data

import com.tube.tv.domain.ContentException
import com.tube.tv.domain.ErrorKind
import java.io.IOException
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException

/** Order matters: the specific extractor exceptions are subclasses of the general ones. */
internal fun Throwable.toContentException(): ContentException = when (this) {
    is ContentException -> this
    is AgeRestrictedContentException -> ContentException(ErrorKind.AGE_RESTRICTED, this)
    is GeographicRestrictionException -> ContentException(ErrorKind.REGION_RESTRICTED, this)
    is PrivateContentException -> ContentException(ErrorKind.PRIVATE, this)
    is AccountTerminatedException -> ContentException(ErrorKind.REMOVED, this)
    is ContentNotAvailableException -> ContentException(ErrorKind.UNAVAILABLE, this)
    is IOException -> ContentException(ErrorKind.NETWORK, this) // includes ReCaptchaException (rate limit)
    is ExtractionException -> ContentException(ErrorKind.EXTRACTION, this)
    else -> ContentException(ErrorKind.UNKNOWN, this)
}
