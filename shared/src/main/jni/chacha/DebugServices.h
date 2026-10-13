/*
 * DebugServices.h — minimal shim for the Apple ChaCha20Poly1305.c algorithm code.
 *
 * This file is DiPlay code, not Apple code. It provides the release-mode Apple AccessorySDK
 * "DebugServices.h" macros the algorithm uses: check() compiles away, require_action_quiet()
 * runs an action and jumps on failure, and DEBUG_HALT() traps (it is only reachable through
 * nonce lengths the JNI bridge rejects before calling).
 */

#ifndef __DebugServices_shim_h__
#define __DebugServices_shim_h__

#define check( X )

#define require_action_quiet( X, LABEL, ACTION )				\
	do															\
	{															\
		if( unlikely( !( X ) ) )								\
		{														\
			{ ACTION; }											\
			goto LABEL;											\
		}														\
	}	while( 0 )

#define DEBUG_HALT()	__builtin_trap()

#endif // __DebugServices_shim_h__