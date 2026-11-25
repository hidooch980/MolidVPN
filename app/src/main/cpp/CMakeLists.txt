cmake_minimum_required(VERSION 3.22.1)
project(molid_native)

# badvpn tun2socks (lwIP-based): builds libtun2socks.so for the in-app Psiphon mode.
add_subdirectory(badvpn)
