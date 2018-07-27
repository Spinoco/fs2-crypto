# fs2-crypto

Essential support of TLS for fs2. 


[![Build Status](https://travis-ci.org/Spinoco/fs2-crypto.svg?branch=series/0.2)](https://travis-ci.org/Spinoco/fs2-crypto)
[![Gitter Chat](https://badges.gitter.im/functional-streams-for-scala/fs2.svg)](https://gitter.im/fs2-crypto/Lobby)

This library adds sport of TLS for  fs2 programs. Library provides two levels of TLS: 

 - **TLSEngine** A Low level abstraction of java's `SSLEngine` allowing asynchronous functional usage of `SSLEngine`
 - **TLSSocket** A wrapper of standard `fs2.io.tcp.Socket` that allows to receive/send data over TLS/SSL
 
### SBT

Add this to your sbt build file :

```
libraryDependencies += "com.spinoco" %% "fs2-crypto" % "0.2.0"
```

### Dependencies

version         |    scala  |   fs2   
----------------|-----------|--------- 
0.3.0-M2        | 2.11, 2.12| 1.0.0-M2
0.2.0           | 2.11, 2.12| 0.10.0
0.1.2           | 2.11, 2.12| 0.9.7  

 
 
## TLSSocket

To create encrypted TLS socket, library allows very simple usage by just using current `fs2.io.tcp.Socket`. 
User just need to create tcp socket and then pass it to `TLSSocket` that creates encrypted version of the socket.

Following is the example how this can be done in scala : 

```scala
import javax.net.ssl._
import java.net.InetSocketAddress

import fs2.io.tcp.client

import spinoco.fs2.crypto._
import spinoco.fs2.crypto.io.tcp.TLSSocket

val ctx = SSLContext.getInstance("TLS")
ctx.init(null, null, null)
    
val engine = sslCtx.createSSLEngine()
engine.setUseClientMode(true) 

val address = new InetSocketAddress("127.0.0.1", 6060)

client(address) flatMap { socket => TLSSocket(socket, engine) } flatMap { tlsSocket =>

  // perform any operations with tlsSocket as you would with normal Socket. 

  ???   
}

```

Note that client initiates initial TLS handshake. As such, client needs to send always some data to server
to trigger SSL handshake. It is engough to send _empty_ chunk of bytes to trigger initial TLS handshake from client side. 

## TLSEngine

Java's `SSLEngine` is not most pleasant api to work with, and therefore there is `TLSEngine`. `TLSEngine` wraps `SSLEngine` and
provides nonblocking asynchronous interface to `SSLEngine` that can be used to create TLS (with java 9 even DTLS) encrypted 
connections. 

`TLSEngine` is used to create `TLSSocket`, which can be used as real example on how TLSEngine can construct the 
higher level abstractions. `TLSEgine` provides two simple methods: 

- **encrypt** to encrypt data from application and send resulting data over transport layer
- **decrypt** to decrypt data received from network transport. 

#### TLSEngine encryption 

When encrypting data with TLSEngine, you use the `encrypt` method that may return any of three results: 
 
 - `Encrypted(data)` indicating that data was successfully encrypted supplying the resulting frame to be sent over network
 - `Closed` indicating that engine is closed and no more application data may be encrypted 
 - `Handshake(data, next)` providing data to be sent to network party and next operation to perfom immediately after the data will 
 be send to remote party, but before any other `encrypt` invocation. 
 
 #### TLSEngine decryption
 
 When decrypting data with TLSEngine, you use the `decrypt` method, that may return any of three possible results: 
 
 - `Decrypted(data)` indicating data that were decrypted. Note that data may be empty, for example in case the received 
 data was not enough to form full TLS Record. If the data supplied to decrypt forms multiple TLS records, then this 
 will return content of both TLS Recors, concatenated. 
 - `Closed` idicating that engine is closed and no more data can be decrypted. 
 - `Handshake(data, signalSentEventually)` providing data to be sent to remote party during handshake. Eventually `signalSentEventually`
 may be provided to perform operation immediately when the `data` will be sent to network, before any other `decrypt`.  
  
 
 
