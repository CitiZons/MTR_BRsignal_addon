package org.mtrbr.api;

import java.util.Collection;
import net.minecraft.server.level.ServerLevel;

/** Supplies faces for a registered device without defining aspect meanings. */
public interface SignalFaceProvider<T extends SignalDevice> {
	Collection<SignalFaceView> faces(ServerLevel level, T device);
}
