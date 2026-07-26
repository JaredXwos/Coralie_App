# Privacy Policy for Coralie

**Effective date:** [26 July 2026]  
**Last updated:** [26 July 2026]

Coralie is an Android application developed and published by **[Jared Xwos]** (“the developer”, “we”, “us”, or “our”).

This Privacy Policy explains how Coralie handles information when you install and use the application.

## 1. Summary

Coralie is a host application for portable, single-file HTML applications (“pages”). It allows pages to use features such as local storage, peer-to-peer communication, HTTPS requests, and timers through the Coralie runtime.

Coralie does not require an account and does not operate a central service that collects user profiles, page content, messages, or browsing activity.

However, pages imported into Coralie may process information, communicate with other users, or contact third-party services. Those pages may have their own privacy practices, which are outside the developer’s control.

## 2. Information collected by the developer

The developer does not directly collect, store, sell, or share personal information through a developer-operated server.

Coralie does not include developer-operated:

- user accounts;
- advertising;
- behavioural analytics;
- tracking profiles;
- cloud storage;
- message history;
- location tracking; or
- marketing systems.

If this changes in a future version, this Privacy Policy and the Google Play Data safety declaration will be updated before the new processing begins.

## 3. Information stored on your device

Coralie stores information locally on your Android device so that the application can function.

This may include:

- imported HTML files and related page metadata;
- page names and configuration;
- declared page capabilities;
- permission decisions;
- storage-space assignments;
- key-value data written by pages;
- bookmarks, preferences, or game data created by pages;
- timer information;
- a locally generated Coralie cryptographic identity; and
- application settings.

This information remains on your device unless a page explicitly transmits it using a capability you have permitted.

You can remove locally stored information by deleting a page or storage space within Coralie, clearing Coralie’s application data through Android settings, or uninstalling the application.

Uninstalling Coralie normally removes its locally stored application data, subject to Android backup, device-management, or operating-system behaviour outside our control.

## 4. Imported HTML pages

Coralie can run HTML pages imported or selected by the user. An imported page is executable code and may determine what information it requests, stores, displays, sends to peers, or sends to third-party internet services.

Coralie uses capability declarations and permission prompts to limit access to supported native functions. Depending on the page, you may be asked to allow access for the current session, allow access persistently, or reject access.

The developer does not review, endorse, or control every page that may be imported into Coralie.

Before importing or using a page from another person or organisation, you should review its source, documentation, and privacy practices. Do not grant capabilities to a page you do not trust.

## 5. Peer-to-peer communication

Pages with permission to use the Coralie mesh capability can establish peer-to-peer connections with other Coralie users or compatible browser clients.

To establish and maintain these connections, Coralie may process or transmit:

- a pseudonymous public key;
- encrypted signalling messages;
- WebRTC session information;
- network addresses and connection metadata;
- peer availability and connection status; and
- application messages chosen by the page.

Application messages are sent between participating peers through WebRTC data channels after a connection is established.

Other participants may receive information that a page sends through the peer-to-peer connection. The developer does not receive or centrally store these messages.

Peer-to-peer communication may reveal network metadata, including an internet protocol address, to connected peers or the network services used to establish the connection. This is an inherent characteristic of direct network communication.

Do not send personal, confidential, or sensitive information through a page unless you understand and trust that page’s communication protocol and the other participants.

## 6. Signalling relays and connection services

Coralie uses third-party network infrastructure to establish peer-to-peer connections.

This may include:

- Nostr relays used to exchange encrypted connection-signalling messages; and
- STUN servers used by WebRTC to determine available network paths.

These services may receive technical information such as:

- your internet protocol address;
- connection timestamps;
- relay or protocol metadata;
- a Coralie public key;
- encrypted signalling content; and
- other information normally processed when connecting to an internet service.

The operators of these services process information under their own terms and privacy policies. Coralie does not control their logging, retention, or security practices.

Coralie currently does not use a developer-operated TURN relay for ordinary application traffic.

## 7. HTTPS requests made by pages

Pages with permission to use the HTTP capability can ask Coralie to make HTTPS requests.

Before a page contacts a domain, Coralie may ask you to approve access to that destination. If approved, the destination service may receive:

- your internet protocol address;
- request headers;
- request bodies;
- query parameters;
- identifiers or credentials supplied by the page;
- response-related metadata; and
- other information included by the page.

These requests are made for the page’s functionality and are subject to the privacy policy and terms of the destination service.

The developer does not receive the contents of these requests or responses unless the destination service is separately operated by the developer and this is clearly disclosed.

Review a page before permitting it to contact a domain, particularly if the page includes authentication credentials, personal information, or sensitive content.

## 8. Permissions

Coralie uses page-level permissions to control access to supported capabilities.

These may include:

- **Mesh:** peer-to-peer communication;
- **Storage:** persistent local key-value storage;
- **HTTP:** HTTPS requests to approved domains; and
- **Timers:** host-managed timers.

A permission allows the page to use the corresponding function. It does not mean that the developer receives the resulting information.

Permissions may be stored locally so that Coralie can remember your decision. You may revoke persistent permissions through the application where supported, delete the relevant page data, clear Coralie’s application data, or uninstall the application.

Android system permissions, if requested, are used only for the function explained at the time of request.

## 9. Data sharing

The developer does not sell personal information.

The developer does not share personal information with advertisers, data brokers, or marketing networks through Coralie.

Information may nevertheless be transmitted:

- to peers selected or discovered through a page’s protocol;
- to Nostr relays and STUN services used for connection establishment;
- to internet domains contacted by an approved page;
- to Google as part of downloading, licensing, updating, securing, or operating an Android application distributed through Google Play; or
- when required by applicable law or a valid legal process.

These transmissions occur as part of providing the requested application or page functionality and do not mean that the developer centrally collects the information.

## 10. Data retention and deletion

The developer does not maintain a central Coralie user database.

Data stored locally remains until it is:

- removed by the page;
- removed through Coralie’s page, permission, or storage controls;
- cleared through Android settings;
- removed by uninstalling Coralie; or
- otherwise removed by the Android operating system.

Data transmitted to peers, relays, STUN servers, or third-party HTTP services may be retained by those recipients according to their own policies. The developer cannot delete information held by independent third parties.

Because Coralie does not provide user accounts or developer-operated cloud storage, there is no separate online Coralie account-deletion process.

## 11. Security

Coralie uses measures intended to reduce unauthorised access, including capability declarations, permission prompts, storage separation, HTTPS requests, encrypted signalling, and controlled access to the native application bridge.

No system can guarantee complete security. Imported HTML pages are executable code, and direct communication with peers or third-party services carries inherent risk.

You are responsible for deciding which pages to import, which permissions to grant, which peers to connect to, and which services to contact.

## 12. Children’s privacy

Coralie is not specifically designed to collect personal information from children.

Because users can import independently created pages, parents, guardians, educators, and other supervising adults should review a page before allowing a child to use it. They should also consider what the page stores, what permissions it requests, who it communicates with, and which third-party services it contacts.

If you believe that the developer has directly received personal information from a child through a developer-operated channel, contact us using the details below.

## 13. International processing

Peer-to-peer participants, Nostr relays, STUN servers, HTTP destinations, Google services, and other third-party infrastructure may be located in countries other than your own.

Information transmitted to these parties may therefore be processed in jurisdictions with different privacy and data-protection laws.

## 14. Third-party services

Coralie may interoperate with services that are not owned or controlled by the developer. Their handling of information is governed by their own privacy policies and terms.

This Privacy Policy applies only to the Coralie Android application and the developer’s handling of information. It does not apply to:

- imported HTML pages created by third parties;
- websites or APIs contacted by those pages;
- other peer participants;
- Nostr relay operators;
- STUN service operators;
- Google Play or other Google services; or
- external links opened from a page.

## 15. Changes to this Privacy Policy

We may update this Privacy Policy when Coralie’s features, data practices, dependencies, or legal obligations change.

The revised policy will show a new “Last updated” date. Material changes may also be communicated through the application, its Google Play listing, or the project repository where appropriate.

## 16. Contact

For privacy questions, requests, or concerns relating to Coralie, contact:

**Developer:** [Jared Xwos]  
**Email:** [jaredxwos@gmail.com]  
**Country or region:** Singapore

When contacting us, describe the issue and identify the relevant Coralie version where possible.
