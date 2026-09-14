package dev.jvmd.resolver.engine;

import java.util.Map;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Provides;
import org.codehaus.plexus.components.secdispatcher.*;
import org.codehaus.plexus.components.secdispatcher.internal.cipher.AESGCMNoPadding;
import org.codehaus.plexus.components.secdispatcher.internal.dispatchers.*;
import org.codehaus.plexus.components.secdispatcher.internal.sources.*;

/** Implements 4.3: native credential providers following Apache Maven 4's standalone SecDispatcherProvider. */
public final class Credentials {
    private Credentials() { }
    @Provides @Named(LegacyDispatcher.NAME) public static Dispatcher legacy() { return new LegacyDispatcher(); }
    @Provides @Named(MasterDispatcher.NAME) public static Dispatcher master(Map<String,Cipher> ciphers,Map<String,MasterSource> sources) { return new MasterDispatcher(ciphers,sources); }
    @Provides @Named(AESGCMNoPadding.CIPHER_ALG) public static Cipher cipher() { return new AESGCMNoPadding(); }
    @Provides @Named(EnvMasterSource.NAME) public static MasterSource environment() { return new EnvMasterSource(); }
    @Provides @Named(SystemPropertyMasterSource.NAME) public static MasterSource property() { return new SystemPropertyMasterSource(); }
    @Provides @Named(GpgAgentMasterSource.NAME) public static MasterSource gpg() { return new GpgAgentMasterSource(); }
    @Provides @Named(PinEntryMasterSource.NAME) public static MasterSource pinEntry() { return new PinEntryMasterSource(); }
}
