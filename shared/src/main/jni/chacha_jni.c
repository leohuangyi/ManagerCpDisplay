/*
 * chacha_jni.c
 *
 * JNI bridge to ChaCha20-Poly1305 for CarPlay screen frame decryption.
 *
 * The ChaCha20/Poly1305 implementation in chacha/ is Apple's AccessorySDK algorithm code
 * (ChaCha20Poly1305.c/.h, Copyright (C) 2013-2016 Apple Inc.), redistributed in binary form
 * with its license notice retained in those verbatim files. The small compatibility headers
 * next to it and this JNI bridge are DiPlay code.
 */

#include <jni.h>
#include <stdint.h>
#include <stddef.h>

#include "ChaCha20Poly1305.h"

/*
 * Class:     com_shilapi_xcertplay_airplay_NativeChacha
 * Method:    decryptFrame
 *
 * Decrypts bodyArray (ciphertext followed by a 16-byte tag) with a 12-byte nonce and aadArray
 * as AAD. Returns the plaintext (bodyArray size minus the tag), or null when the tag does not
 * verify or the arguments are invalid. bodyArray is never modified.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChacha_decryptFrame(
	JNIEnv *env, jclass clazz, jbyteArray keyArray, jbyteArray nonceArray,
	jbyteArray aadArray, jbyteArray bodyArray)
{
	const jsize		bodyLen  = (*env)->GetArrayLength( env, bodyArray );
	const jsize		keyLen   = (*env)->GetArrayLength( env, keyArray );
	const jsize		nonceLen = (*env)->GetArrayLength( env, nonceArray );
	const jsize		aadLen   = (*env)->GetArrayLength( env, aadArray );
	jsize			ctLen;
	jbyte *			key		= NULL;
	jbyte *			nonce	= NULL;
	jbyte *			aad		= NULL;
	jbyte *			body	= NULL;
	jbyte *			out		= NULL;
	jbyteArray		outArray = NULL;
	OSStatus		err;

	(void) clazz;
	if( !bodyArray || ( bodyLen < 16 ) || ( keyLen != 32 ) || ( nonceLen != 12 ) ) return( NULL );
	ctLen = bodyLen - 16;

	key		= (*env)->GetByteArrayElements( env, keyArray, NULL );
	nonce	= (*env)->GetByteArrayElements( env, nonceArray, NULL );
	aad		= ( aadLen > 0 ) ? (*env)->GetByteArrayElements( env, aadArray, NULL ) : NULL;
	body	= (*env)->GetByteArrayElements( env, bodyArray, NULL );
	if( !key || !nonce || ( aadLen > 0 && !aad ) || !body ) goto exit;

	outArray = (*env)->NewByteArray( env, ctLen );
	if( !outArray ) goto exit; // OutOfMemoryError is pending and reaches the caller.

	out = (*env)->GetByteArrayElements( env, outArray, NULL );
	if( !out )
	{
		(*env)->DeleteLocalRef( env, outArray );
		outArray = NULL;
		goto exit;
	}

	err = chacha20_poly1305_decrypt_all_96x32(
		(const uint8_t *) key, (const uint8_t *) nonce,
		(const void *) aad, (size_t) aadLen,
		(const void *) body, (size_t) ctLen,
		(void *) out, (const uint8_t *) ( body + ctLen ) );

	(*env)->ReleaseByteArrayElements( env, outArray, out, ( err == kNoErr ) ? 0 : JNI_ABORT );
	if( err != kNoErr )
	{
		(*env)->DeleteLocalRef( env, outArray );
		outArray = NULL;
	}

exit:
	if( key )	(*env)->ReleaseByteArrayElements( env, keyArray, key, JNI_ABORT );
	if( nonce )	(*env)->ReleaseByteArrayElements( env, nonceArray, nonce, JNI_ABORT );
	if( aad )	(*env)->ReleaseByteArrayElements( env, aadArray, aad, JNI_ABORT );
	if( body )	(*env)->ReleaseByteArrayElements( env, bodyArray, body, JNI_ABORT );
	return( outArray );
}