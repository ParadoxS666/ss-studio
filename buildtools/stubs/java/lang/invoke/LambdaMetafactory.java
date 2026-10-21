package java.lang.invoke;

/** Compile-time stub only (Android's android.jar omits it; d8 desugars lambdas). Never packaged. */
public final class LambdaMetafactory {
    public static CallSite metafactory(MethodHandles.Lookup caller, String name, MethodType type,
            MethodType sam, MethodHandle impl, MethodType inst) throws LambdaConversionException {
        throw new UnsupportedOperationException();
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller, String name, MethodType type,
            Object... args) throws LambdaConversionException {
        throw new UnsupportedOperationException();
    }
}
