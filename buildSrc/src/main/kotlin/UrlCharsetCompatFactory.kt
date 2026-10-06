import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * URLDecoder.decode(String, Charset) and URLEncoder.encode(String, Charset) only exist from Android 13 (API 33).
 * NewPipeExtractor calls them, which crashes older devices with NoSuchMethodError. This rewrites those calls
 * (in every class that goes into the APK) to com.tube.tv.compat.UrlCompat, which uses the String-charset overloads.
 */
abstract class UrlCharsetCompatFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor {
                val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, mv) {
                    override fun visitMethodInsn(
                        opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean,
                    ) {
                        if (opcode == Opcodes.INVOKESTATIC && descriptor == CHARSET_DESCRIPTOR &&
                            ((owner == "java/net/URLDecoder" && name == "decode") ||
                                (owner == "java/net/URLEncoder" && name == "encode"))
                        ) {
                            val helper = if (name == "decode") "decode" else "encode"
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, COMPAT, helper, descriptor, false)
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            }
        }

    // The helper itself must keep calling the real String-charset overloads.
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className != "com.tube.tv.compat.UrlCompat"

    private companion object {
        const val COMPAT = "com/tube/tv/compat/UrlCompat"
        const val CHARSET_DESCRIPTOR = "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
    }
}
