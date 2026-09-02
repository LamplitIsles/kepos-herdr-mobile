# Ubiquitous Language

## Connection Profile

A saved, non-secret description of one direct SSH destination: host, port, user, host-key policy, and the chosen Private Key reference.

## Private Key

An SSH identity selected by the owner for public-key authentication. Its private material is never a connection-profile field or an application-visible string.

## Remote Session

One interactive SSH terminal connection that starts and exchanges input/output with Herdr on the selected Connection Profile.
