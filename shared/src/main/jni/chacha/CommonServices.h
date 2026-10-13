/*
 * CommonServices.h — minimal shim for the Apple ChaCha20Poly1305.c algorithm code.
 *
 * This file is DiPlay code, not Apple code. It provides the small slice of the Apple AccessorySDK
 * "CommonServices.h" surface that the verbatim ChaCha20Poly1305.c/.h files use, matching the
 * original definitions for a little-endian host: OSStatus and Boolean, kNoErr/kAuthenticationErr,
 * little-endian load/store macros, rotation macros, and the constant-time compare and secure zero
 * helpers.
 */

#ifndef __CommonServices_shim_h__
#define __CommonServices_shim_h__

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>
#include <string.h>

typedef int32_t		OSStatus;
typedef uint8_t		Boolean;

#define kNoErr				0
#define kAuthenticationErr	( -6754 )

#define TARGET_HAS_NEON		0
#define TARGET_HAS_SSE		0
#define SSE_VERSION( MAJOR, MINOR )			( ( ( MAJOR ) * 10 ) + ( MINOR ) )

#define unlikely( EXPRESSION )				__builtin_expect( !!( EXPRESSION ), 0 )

// Little-endian host loads/stores (the Apple originals are direct casts on little-endian targets).
#define ReadLittle32( PTR )					( *( (const uint32_t *) ( PTR ) ) )
#define WriteLittle32( PTR, VALUE )			do { *( (uint32_t *) ( PTR ) ) = ( VALUE ); } while( 0 )
#define WriteLittle64( PTR, VALUE )			do { *( (uint64_t *) ( PTR ) ) = ( VALUE ); } while( 0 )

#define ROTL( X, N, SIZE )					( ( ( X ) << ( N ) ) | ( ( X ) >> ( ( SIZE ) - ( N ) ) ) )
#define ROTL32( X, N )						ROTL( ( X ), ( N ), 32 )

#define static_analyzer_mem_zeroed( PTR, LEN )	memset( ( PTR ), 0, ( LEN ) )

static inline int	memcmp_constant_time( const void *inA, const void *inB, size_t inLen )
{
	const uint8_t * const	a = (const uint8_t *) inA;
	const uint8_t * const	b = (const uint8_t *) inB;
	int						result = 0;
	size_t					i;

	for( i = 0; i < inLen; ++i ) result |= ( a[ i ] ^ b[ i ] );
	return( result );
}

static inline void	MemZeroSecure( void *inPtr, size_t inLen )
{
	volatile uint8_t *		ptr = (volatile uint8_t *) inPtr;

	while( inLen-- ) *ptr++ = 0;
}

#endif // __CommonServices_shim_h__