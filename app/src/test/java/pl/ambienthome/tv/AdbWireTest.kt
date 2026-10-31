package pl.ambienthome.tv

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

class AdbWireTest {
    private val keys get() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private data class P(val cmd:Int,val a:Int,val b:Int,val bytes:ByteArray)
    private fun read(input:InputStream):P {
        fun full(n:Int):ByteArray { val b=ByteArray(n); var at=0; while(at<n){ val r=input.read(b,at,n-at); check(r>0); at+=r }; return b }
        val h=ByteBuffer.wrap(full(24)).order(ByteOrder.LITTLE_ENDIAN); val c=h.int; val a=h.int;val b=h.int;val n=h.int;h.int;h.int; return P(c,a,b,full(n))
    }
    private fun send(out:OutputStream,cmd:Int,a:Int,b:Int,data:ByteArray=byteArrayOf(),checksum:Int=data.sumOf{it.toInt() and 255}) {
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a).putInt(b).putInt(data.size).putInt(checksum).putInt(cmd.inv()).array());out.write(data);out.flush()
    }
    @Test fun fragmentedShellOutputIsAcknowledgedAndExitCodeParsed() {
        val server=ServerSocket(0); val pool=Executors.newSingleThreadExecutor()
        val future=pool.submit { server.accept().use { s -> s.soTimeout=5000;val i=s.getInputStream();val o=s.getOutputStream(); assertEquals(0x4e584e43,read(i).cmd);send(o,0x4e584e43,0x01000000,4096,"device::\u0000".toByteArray());val open=read(i);assertEquals(0x4e45504f,open.cmd);assertTrue(String(open.bytes).contains("shell:id -u; printf"));send(o,0x59414b4f,7,1);for(t in listOf("20","00\n__AMBIENT_EXIT_38C7__0\n")){send(o,0x45545257,7,1,t.toByteArray());assertEquals(0x59414b4f,read(i).cmd)};send(o,0x45534c43,7,1);assertEquals(0x45534c43,read(i).cmd) } }
        try { val result=AdbWire(keys).execute("127.0.0.1",server.localPort,"id -u");assertEquals("2000",result.output);assertEquals(0,result.code);future.get(10,TimeUnit.SECONDS) } finally {server.close();pool.shutdownNow()}
    }
    @Test fun rsaAuthSignsTheExistingDigestInsteadOfHashingItAgain() {
        val pair=keys;val server=ServerSocket(0);val pool=Executors.newSingleThreadExecutor();val token=ByteArray(20){it.toByte()}
        val future=pool.submit {server.accept().use{s->s.soTimeout=5000;val i=s.getInputStream();val o=s.getOutputStream();read(i);send(o,0x48545541,1,0,token);val auth=read(i);assertEquals(2,auth.a);val verified=Cipher.getInstance("RSA/ECB/PKCS1Padding").run{init(Cipher.DECRYPT_MODE,pair.public);doFinal(auth.bytes)};assertEquals(35,verified.size);assertArrayEquals(token,verified.takeLast(20).toByteArray());send(o,0x4e584e43,0x01000000,4096,"device::\u0000".toByteArray());read(i);send(o,0x59414b4f,7,1);send(o,0x45545257,7,1,"denied\n__AMBIENT_EXIT_38C7__7\n".toByteArray());read(i);send(o,0x45534c43,7,1);read(i)}}
        try{val r=AdbWire(pair).execute("127.0.0.1",server.localPort,"id -u");assertEquals(7,r.code);future.get(10,TimeUnit.SECONDS)}finally{server.close();pool.shutdownNow()}
    }
    @Test fun publicKeyUsesAdbLittleEndian2048BitStructure() { val bytes=AdbWire(keys).publicAdbKey();val blob=Base64.getDecoder().decode(String(bytes).substringBefore(' '));assertEquals(524,blob.size);val b=ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);assertEquals(64,b.int);b.position(520);assertEquals(65537,b.int);assertEquals(0,bytes.last().toInt()) }
    @Test fun badChecksumIsRejectedBeforeOpeningShell() {
        val server=ServerSocket(0);val pool=Executors.newSingleThreadExecutor();val f=pool.submit{server.accept().use{s->read(s.getInputStream());send(s.getOutputStream(),0x4e584e43,0x01000000,4096,"device::\u0000".toByteArray(),123)}}
        try{try{AdbWire(keys).execute("127.0.0.1",server.localPort,"id -u");fail("Bad checksum accepted")}catch(_:IllegalStateException){};f.get(10,TimeUnit.SECONDS)}finally{server.close();pool.shutdownNow()}
    }
    @Test fun untrustedKeyIsOfferedOnceAndRefusalNeverOpensShell() {
        val server=ServerSocket(0); val pool=Executors.newSingleThreadExecutor()
        val f=pool.submit { server.accept().use { s ->
            s.soTimeout=5000;val i=s.getInputStream();val o=s.getOutputStream();read(i)
            send(o,0x48545541,1,0,ByteArray(20));assertEquals(2,read(i).a)
            send(o,0x48545541,1,0,ByteArray(20){1});val pub=read(i);assertEquals(3,pub.a);assertEquals(0,pub.bytes.last().toInt())
            send(o,0x48545541,1,0,ByteArray(20){2})
        } }
        try { try { AdbWire(keys).execute("127.0.0.1",server.localPort,"id -u");fail("Untrusted connection accepted") } catch (_:IllegalStateException) {}; f.get(10,TimeUnit.SECONDS) }
        finally {server.close();pool.shutdownNow()}
    }
}
