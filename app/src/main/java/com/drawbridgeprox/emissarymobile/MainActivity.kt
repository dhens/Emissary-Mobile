package com.drawbridgeprox.emissarymobile

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.drawbridgeprox.emissarymobile.ui.theme.EmissaryMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.io.path.absolutePathString

class MainActivity : ComponentActivity() {



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EmissaryMobileTheme {}
            DrawbridgeApp(
                connectToDrawbridge = { context, address ->
                    // TODO: Implement the actual logic to connect to the Drawbridge
                    // This is where you would typically make an API call or perform any necessary operations
                    // For demonstration purposes, we'll just display a Toast message
                    // test
                    establishMTLSConnection(context, address)
                    Toast.makeText(this, "Connecting to Drawbridge at $address", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }


}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    EmissaryMobileTheme{}
}

@Composable
fun DrawbridgeApp(connectToDrawbridge: (Context, String) -> Unit) {
    var address = remember { mutableStateOf("") }
    val context = LocalContext.current

    val drawbridgeFile = context.filesDir.toPath().resolve("bundle/drawbridge.txt")
    if (Files.exists(drawbridgeFile)) {
        // TODO
        // check if lookup is null - otherwise we can encounter runtime exception
        address.value = Files.readAllLines(drawbridgeFile)[0]
    }


    val selectZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                saveZipToAppDirectory(context, it)
            }
        }
    )

    Column(
        modifier = Modifier.padding(16.dp)
    ) {
        OutlinedTextField(
            value = address.value,
            onValueChange = { address.value = it },
            label = { Text("Drawbridge Address") },
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                selectZipLauncher.launch(arrayOf("application/zip"))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("Load Bundle ZIP")
        }

        Button(
            onClick = { connectToDrawbridge(context, address.value) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text("Connect to Drawbridge")
        }
    }
}

private fun saveZipToAppDirectory(context: Context, uri: Uri) {
    try {
        val inputStream = context.contentResolver.openInputStream(uri)
        val tempFile = Files.createTempFile(context.cacheDir.toPath(), "temp", ".zip")
        Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING)

        val zipFile = ZipFile(tempFile.toFile())
        val entries = zipFile.entries()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val filePath = context.filesDir.toPath().resolve(entry.name)

            if (entry.isDirectory) {
                if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                    Files.delete(filePath)
                }
                Files.createDirectories(filePath)
            } else {
                val parentPath = filePath.parent
                if (Files.exists(parentPath) && !Files.isDirectory(parentPath)) {
                    Files.delete(parentPath)
                }
                Files.createDirectories(parentPath)
                val inputStream = zipFile.getInputStream(entry)
                Files.copy(inputStream, filePath, StandardCopyOption.REPLACE_EXISTING)
                Log.i("writeFile", filePath.toString())
                inputStream.close()
            }
        }

        zipFile.close()
        Files.delete(tempFile)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun establishMTLSConnection(context: Context, drawbridgeAddress: String) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Load the CA certificate
            val caFile = context.filesDir.toPath().resolve("put_certificates_and_key_from_drawbridge_here/ca.crt")
            Log.i("caFile", caFile.absolutePathString())
            val caInputStream = Files.newInputStream(caFile)
            val certFactory = CertificateFactory.getInstance("X.509")
            val caCert = certFactory.generateCertificate(caInputStream)
            caInputStream.close()

            // Create a KeyStore containing the CA certificate
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            keyStore.load(null, null)
            keyStore.setCertificateEntry("caCert", caCert)

            // Create a TrustManager that trusts the CA certificate
            val tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm()
            val tmf = TrustManagerFactory.getInstance(tmfAlgorithm)
            tmf.init(keyStore)

            // Load the client certificate
            val clientCertFile = context.filesDir.toPath().resolve("put_certificates_and_key_from_drawbridge_here/emissary-mtls-tcp.crt")
            val clientCertInputStream = Files.newInputStream(clientCertFile)
            val clientCert = certFactory.generateCertificate(clientCertInputStream)
            clientCertInputStream.close()

            // Load the client private key in PKCS#8 format
            val privateKeyFile = context.filesDir.toPath().resolve("put_certificates_and_key_from_drawbridge_here/emissary-mtls-tcp.key")
            val privateKeyContent = Files.readAllLines(privateKeyFile).joinToString("\n")
            val privateKeyPem = privateKeyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            val privateKeyDer = Base64.decode(privateKeyPem, Base64.DEFAULT)

            // Convert to PKCS#8 format
            val pkcs8EncodedKeySpec = PKCS8EncodedKeySpec(privateKeyDer)

            // Generate the private key from PKCS#8 bytes
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(pkcs8EncodedKeySpec)

            // Create a KeyStore containing the client certificate and private key
            val clientKeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
            clientKeyStore.load(null, null)
            clientKeyStore.setCertificateEntry("clientCert", clientCert)
            clientKeyStore.setKeyEntry("privateKey", privateKey, null, arrayOf(clientCert))

            // Create a KeyManager using the client certificate and private key
            val kmfAlgorithm = KeyManagerFactory.getDefaultAlgorithm()
            val kmf = KeyManagerFactory.getInstance(kmfAlgorithm)
            kmf.init(clientKeyStore, null)

            // Create an SSLContext that uses our TrustManager and KeyManager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, tmf.trustManagers, null)

            // Create an SSLSocketFactory from the SSLContext
            val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory

            // Split the address and port
            val (address, port) = drawbridgeAddress.split(":").let {
                it[0] to it[1].toInt()
            }

            // Create an SSLSocket using the SSLSocketFactory
            val sslSocket = sslSocketFactory.createSocket(address, port) as SSLSocket

            // Configure the SSLSocket
            val sslParameters = sslSocket.sslParameters
            sslParameters.endpointIdentificationAlgorithm = "HTTPS"
            sslSocket.sslParameters = sslParameters
            sslSocket.startHandshake()

            // Use the SSLSocket for communication
            val inputStream = sslSocket.inputStream
            val outputStream = sslSocket.outputStream

            // Send and receive a message to/from the server
            val writer = outputStream.bufferedWriter()
            val reader = inputStream.bufferedReader()

            writer.write("Hello, Drawbridge Server!\n")
            writer.flush()

            val response = reader.readLine()
            Log.d("MTLSHelper", "Server response: $response")

            // Close the streams and socket
            reader.close()
            writer.close()
            sslSocket.close()
        } catch (e: Exception) {
            Log.e("MTLSHelper", "Error establishing mTLS connection", e)
        }
    }
}
