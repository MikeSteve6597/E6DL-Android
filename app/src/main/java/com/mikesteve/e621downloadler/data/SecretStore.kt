package com.mikesteve.e621downloadler.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 的加密存储（Android Keystore + AES-GCM）。
 *
 * 背景：桌面版把 API Key 明文写在 `userdata.json`，因为 Windows 没有可依赖的系统密钥库；
 * Android 有 Keystore，所以这里做**有意的改进**：明文只存在于内存，落盘的是
 * `[版本字节][12 字节 IV][GCM 密文+认证标签]` 的 Base64。
 *
 * 密钥由系统 Keystore 托管（不可导出），应用被卸载时随之销毁。
 * 解密失败（例如用户清除凭据、刷机、Keystore 失效）时返回 null，UI 会退回未登录状态，
 * 由用户重新填写 API Key——绝不把失败静默当成"已登录"。
 */
class SecretStore {

    /** 返回可直接落盘的 Base64 字符串；失败或无内容返回 null。 */
    fun encrypt(plain: String): String? {
        if (plain.isBlank()) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(1 + iv.size + cipherText.size)
            payload[0] = PAYLOAD_VERSION
            iv.copyInto(payload, destinationOffset = 1)
            cipherText.copyInto(payload, destinationOffset = 1 + iv.size)
            Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "API Key 加密失败（不会退回明文存储）：${e.javaClass.simpleName}")
            null
        }
    }

    /** 解密 [encrypt] 的结果；token 为空或解密失败返回 null。 */
    fun decrypt(token: String?): String? {
        if (token.isNullOrBlank()) return null
        return try {
            val payload = Base64.decode(token, Base64.NO_WRAP)
            if (payload.size <= 1 + IV_BYTES) return null
            if (payload[0] != PAYLOAD_VERSION) return null
            val iv = payload.copyOfRange(1, 1 + IV_BYTES)
            val cipherText = payload.copyOfRange(1 + IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "API Key 解密失败，按未登录处理：${e.javaClass.simpleName}")
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 不要求用户解锁/指纹：本项目是后台下载工具，不引入额外的认证摩擦
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "e6dl_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val PAYLOAD_VERSION: Byte = 1
    }
}
