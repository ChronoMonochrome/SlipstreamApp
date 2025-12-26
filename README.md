
# Slipstream App

Slipstream is a high-performance covert channel over DNS, powered by QUIC multipath. This Android application serves as a Graphical User Interface (GUI) and manager for the Slipstream ecosystem, establishing a secure, device-wide SOCKS5 proxy tunnel.

The app integrates the [SocksDroid](https://github.com/bndeff/socksdroid) VpnService implementation to capture device traffic and route it through the Slipstream tunnel without requiring system-wide proxy settings.

## Features

-   **DNS-over-QUIC Tunneling:** Leverages the `slipstream-client` for high-speed, covert data transmission.
    
-   **VpnService Integration:** Device-wide tunneling via a local VPN interface (no root required for the VPN logic).


## Prerequisites

1.  **External Server (SSH Endpoint):** A server accessible via SSH acting as the remote end of the tunnel.
    
2.  **Binaries:** The following binaries must be present in the project's `assets` or `jni` folders:
    
    -   `slipstream-client`
        
    -   `proxy-client` (Go-based SSH executor)
        
    -   `pdnsd` and `tun2socks` (Compiled via NDK)
        
3.  **Network Configuration:** Remote server IP and the domain name configured for your Slipstream DNS service.
    

## Setup Instructions

The connection uses **SSH Public Key Authentication** to secure the tunnel between the `ssh-client` and your remote server.

### 1. Generate SSH Key Pair (Recommended: Ed25519)

Generate a key pair on your machine:

```
ssh-keygen -t ed25519 -f id_slipstream -N ""

```

### 2. Server-Side Configuration

Copy the contents of `id_slipstream.pub` and append it to the `/root/.ssh/authorized_keys` file on your **remote SSH server**.

### 3. App Configuration

1.  **Key Placement:** Place your private key (`id_slipstream`) in the application's internal storage directory and choose via file picker.
    
2.  **Connection Details:** Enter the **Server IP** and **DNS Hostname** in the app's main interface.
    
3.  **Start Service:** Tap **Start**. The app will request permission to establish a VPN connection.
    

## How it Works

1.  **Slipstream Layer:** The app executes `slipstream-client`, connecting to the remote DNS server to establish the QUIC carrier.
    
2.  **SSH Layer:** The [go-ssh-client](https://github.com/ChronoMonochrome/go-ssh-client) connects through the Slipstream pipe to the remote server, opening a SOCKS5 proxy listener on `127.0.0.1:3080`.
    
3.  **VPN Layer:** The Android `VpnService` (based on SocksDroid) creates a virtual `tun0` interface.
    
4.  **Routing:**
    
    -   Traffic is captured by `tun0`.
        
    -   `tun2socks` converts IP packets to TCP streams.
        
    -   Streams are forwarded to the SOCKS5 proxy at port `3080`.
        
    -   `pdnsd` handles DNS resolution to prevent leaks.
        

## Credits & Resources

-   **Slipstream:** [EndPositive/slipstream](https://github.com/EndPositive/slipstream)
    
-   **VPN Logic:** Inspired by [bndeff/socksdroid](https://github.com/bndeff/socksdroid )
