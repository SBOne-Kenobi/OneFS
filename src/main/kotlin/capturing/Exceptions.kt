package capturing

import java.lang.Exception


open class AccessException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

interface CaptureException
class ReadCaptureException: CaptureException, AccessException("Failed to capture read access")
class WriteCaptureException: CaptureException, AccessException("Failed to capture write access")