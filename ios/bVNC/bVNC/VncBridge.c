//
//  VncBridge.c
//  bVNC
//
//  Created by iordan iordanov on 2019-12-26.
//  Copyright © 2019 iordan iordanov. All rights reserved.
//

#include <pthread/pthread.h>
#include "VncBridge.h"
#include "ucs2xkeysym.h"
#include "SshPortForwarder.h"
#include "Utility.h"
#include <signal.h>

char* USERNAME = NULL;
char* PASSWORD = NULL;
int pixel_buffer_size = 0;
int BYTES_PER_PIXEL = 4;
int fbW = 0;
int fbH = 0;

bool getMaintainConnection(void *c) {
    rfbClient *cl = (rfbClient *)c;
    if (cl != NULL) {
        return cl->maintainConnection;
    } else {
        return 0;
    }
}

void setMaintainConnection(void *c, int state) {
    rfbClient *cl = (rfbClient *)c;
    if (cl != NULL) {
        cl->maintainConnection = state;
    }
}


static rfbCredential* get_credential(rfbClient *cl, int credentialType){
    rfbClientLog("VeNCrypt authentication callback called\n\n");
    rfbCredential *c = malloc(sizeof(rfbCredential));
    
    if(credentialType == rfbCredentialTypeUser) {
        rfbClientLog("Username and password requested for authentication, initializing now\n");
        c->userCredential.username = malloc(RFB_BUF_SIZE);
        c->userCredential.password = malloc(RFB_BUF_SIZE);
        strcpy(c->userCredential.username, USERNAME);
        strcpy(c->userCredential.password, PASSWORD);
        /* remove trailing newlines */
        c->userCredential.username[strcspn(c->userCredential.username, "\n")] = 0;
        c->userCredential.password[strcspn(c->userCredential.password, "\n")] = 0;
    } else if (credentialType == rfbCredentialTypeX509) {
        rfbClientLog("x509 certificates requested for authentication, initializing now\n");
        c->x509Credential.x509CrlVerifyMode = rfbX509CrlVerifyNone;
        c->x509Credential.x509CACrlFile = NULL;
        c->x509Credential.x509CACertFile = NULL;
        c->x509Credential.x509ClientKeyFile = NULL;
        c->x509Credential.x509ClientCertFile = NULL;
    }
    
    return c;
}

static char* get_password(rfbClient *cl){
    rfbClientLog("VNC password authentication callback called\n\n");
    char *p = malloc(RFB_BUF_SIZE);
    
    rfbClientLog("Password requested for authentication\n");
    strcpy(p, PASSWORD);
    
    /* remove trailing newlines */
    return p;
}

static void update (rfbClient *cl, int x, int y, int w, int h) {
    //rfbClientLog("Update received\n");
    if (!framebuffer_update_callback(cl->instance, cl->frameBuffer, fbW, fbH, x, y, w, h)) {
        // This session is a left-over backgrounded session and must quit.
        printf("Must quit background session with instance number %d\n", cl->instance);
        cl->maintainConnection = false;
    }
}

static rfbBool resize (rfbClient *cl) {
    rfbClientLog("Resize RFB Buffer, allocating buffer\n");
    fbW = cl->width;
    fbH = cl->height;
    rfbClientLog("Width, height: %d, %d\n", fbW, fbH);
    
    uint8_t* oldFrameBuffer = cl->frameBuffer;
    pixel_buffer_size = BYTES_PER_PIXEL*fbW*fbH*sizeof(char);
    cl->frameBuffer = (uint8_t*)malloc(pixel_buffer_size);
    framebuffer_resize_callback(cl->instance, cl, fbW, fbH);
    update(cl, 0, 0, fbW, fbH);
    if (oldFrameBuffer != NULL) {
        free(oldFrameBuffer);
    }
    return TRUE;
}

void disconnectVnc(void *c) {
    rfbClient *cl = (rfbClient *)c;
    printf("Setting maintainConnection to false\n");
    if (cl != NULL) {
        cl->maintainConnection = false;
        // Force force some communication with server in order to wake up the
        // background thread waiting for server messages.
        SendFramebufferUpdateRequest(cl, 0, 0, 1, 1, FALSE);
    }
}

void sendWholeScreenUpdateRequest(void *c, bool fullScreenUpdate) {
    rfbClient *cl = (rfbClient *)c;
    if (cl != NULL && cl->maintainConnection) {
        SendFramebufferUpdateRequest(cl, 0, 0, cl->width, cl->height, fullScreenUpdate);
    }
}

int ssl_certificate_verification_callback(rfbClient *client, char* issuer, char* common_name,
char* fingerprint_sha256, char* fingerprint_sha512, int pday, int psec) {
    char user_message[8192];

    char validity[8];
    snprintf(validity, 8, "Invalid");
    if (pday >= 0 && psec > 0) {
        snprintf(validity, 8, "Valid");
    }

    snprintf(user_message, 8191,
            "Issuer: %s\n\nCommon name: %s\n\nSHA256 Fingerprint: %s\n\nSHA512 Fingerprint: %s\n\n%s for %d days and %d seconds.\n",
            issuer, common_name, fingerprint_sha256, fingerprint_sha512, validity, pday, psec);
    
    int response = yes_no_callback(client->instance, (int8_t *)"Please verify VNC server certificate", (int8_t *)user_message,
                                   (int8_t *)fingerprint_sha256, (int8_t *)fingerprint_sha512, (int8_t *)"X509");

    return response;
}

rfbBool lockWriteToTLS(rfbClient *client) {
    lock_write_tls_callback(client->instance);
    return TRUE;
}

rfbBool unlockWriteToTLS(rfbClient *client) {
    unlock_write_tls_callback(client->instance);
    return TRUE;
}

void signal_handler(int signal, siginfo_t *info, void *reserved) {
    printf("Handling signal: %d\n", signal);
    failure_callback(-1, NULL);
}

static void handle_signals() {
    struct sigaction handler;
    memset(&handler, 0, sizeof(handler));
    handler.sa_sigaction = signal_handler;
    handler.sa_flags = SA_SIGINFO;
    sigaction(SIGILL, &handler, NULL);
    sigaction(SIGABRT, &handler, NULL);
    sigaction(SIGBUS, &handler, NULL);
    sigaction(SIGFPE, &handler, NULL);
    sigaction(SIGSEGV, &handler, NULL);
    sigaction(SIGPIPE, &handler, NULL);
    sigaction(SIGINT, &handler, NULL);
}

void *initializeVnc(int instance,
                   bool (*fb_update_callback)(int instance, uint8_t *, int fbW, int fbH, int x, int y, int w, int h),
                   void (*fb_resize_callback)(int instance, void *, int fbW, int fbH),
                   void (*fail_callback)(int instance, uint8_t *),
                   void (*cl_log_callback)(int8_t *),
                   void (*lock_wrt_tls_callback)(int instance),
                   void (*unlock_wrt_tls_callback)(int instance),
                   int (*y_n_callback)(int instance, int8_t *, int8_t *, int8_t *, int8_t *, int8_t *),
                   char* addr, char* user, char* password) {
    rfbClientLog("Initializing VNC session.\n");
    handle_signals();
    USERNAME = user;
    PASSWORD = password;
    framebuffer_update_callback = fb_update_callback;
    framebuffer_resize_callback = fb_resize_callback;
    failure_callback = fail_callback;
    client_log_callback = cl_log_callback;
    yes_no_callback = y_n_callback;
    lock_write_tls_callback = lock_wrt_tls_callback;
    unlock_write_tls_callback = unlock_wrt_tls_callback;

    rfbClientLog = rfbClientErr = client_log;
    rfbClient *cl = NULL;
    int argc = 6;
    char **argv = (char**)malloc(argc*sizeof(char*));
    int i = 0;
    for (i = 0; i < argc; i++) {
        //rfbClientLog("%d\n", i);
        argv[i] = (char*)malloc(256*sizeof(char));
    }
    strcpy(argv[0], "dummy");
    strcpy(argv[1], "-compress");
    strcpy(argv[2], "8");
    strcpy(argv[3], "-quality");
    strcpy(argv[4], "7");
    strcpy(argv[5], addr);

    /* 16-bit: cl=rfbGetClient(5,3,2); */
    cl=rfbGetClient(8,3,BYTES_PER_PIXEL);
    cl->MallocFrameBuffer=resize;
    cl->canHandleNewFBSize = TRUE;
    cl->GotFrameBufferUpdate=update;
    //cl->HandleKeyboardLedState=kbd_leds;
    //cl->HandleTextChat=text_chat;
    //cl->GotXCutText = got_selection;
    cl->GetCredential = get_credential;
    cl->GetPassword = get_password;
    //cl->listenPort = LISTEN_PORT_OFFSET;
    //cl->listen6Port = LISTEN_PORT_OFFSET;
    cl->SslCertificateVerifyCallback = ssl_certificate_verification_callback;
    cl->LockWriteToTLS = lockWriteToTLS;
    cl->UnlockWriteToTLS = unlockWriteToTLS;
    cl->instance = instance;
    
    if (!rfbInitClient(cl, &argc, argv)) {
        cl = NULL; /* rfbInitClient has already freed the client struct */
        cleanup(cl, "Failed to connect to server\n");
    }
    printf("Done initializing VNC session\n");
    return (void *)cl;
}

void connectVnc(void *c) {
    rfbClientLog("Setting up connection.\n");
    rfbClient *cl = (rfbClient *)c;
    cl->maintainConnection = true;
    int i;
        
    while (cl != NULL) {
        i = WaitForMessage(cl, 16000);
        if (cl->maintainConnection != true) {
            cleanup(cl, NULL);
            break;
        }
        if (i < 0) {
            cleanup(cl, "Connection to server failed\n");
            break;
        }
        //if (i) {
            //rfbClientLog("Handling RFB Server Message\n");
        //}
        
        if (!HandleRFBServerMessage(cl)) {
            cleanup(cl, "Connection to server failed\n");
            break;
        }
    }
    rfb_client_cleanup(cl);
    printf("Background thread exiting connectVnc function.\n\n");
    rfbClientLog("Connection terminating.\n\n");
}

void rfb_client_cleanup(rfbClient *cl) {
    if (cl != NULL) {
        if (cl->frameBuffer != NULL) {
            free(cl->frameBuffer);
        }
        //rfbClientCleanup(cl);
    }
}

void cleanup(rfbClient *cl, char *message) {
    rfbClientLog("%s", message);
    
    if (cl != NULL) {
        cl->maintainConnection = false;
        failure_callback(cl->instance, (uint8_t*)message);
    }
}

// TODO: Replace with real conversion table
struct { char mask; int bits_stored; } utf8Mapping[] = {
        {0b00111111, 6},
        {0b01111111, 7},
        {0b00011111, 5},
        {0b00001111, 4},
        {0b00000111, 3},
        {0,0}
};

/* UTF-8 decoding is from https://rosettacode.org/wiki/UTF-8_encode_and_decode which is under GFDL 1.2 */
static rfbKeySym utf8char2rfbKeySym(const char chr[4]) {
        int bytes = (int)strlen(chr);
        //rfbClientLog("Number of bytes in %s: %d\n", chr, bytes);

        int shift = utf8Mapping[0].bits_stored * (bytes - 1);
        rfbKeySym codep = (*chr++ & utf8Mapping[bytes].mask) << shift;
        int i;
        for(i = 1; i < bytes; ++i, ++chr) {
                shift -= utf8Mapping[0].bits_stored;
                codep |= ((char)*chr & utf8Mapping[0].mask) << shift;
        }
        //printf("utf8char2rfbKeySym %s converted to %#06x\n", chr, codep);
        return codep;
}

void sendUniDirectionalKeyEvent(void *c, const char *characters, bool down) {
    rfbClient *cl = (rfbClient *)c;

    if (!cl->maintainConnection) {
        return;
    }
    rfbKeySym sym = utf8char2rfbKeySym(c);
    //printf("sendUniDirectionalKeyEvent converted %s to xkeysym: %#06x\n", characters, sym);
    sendUniDirectionalKeyEventWithKeySym(cl, sym, down);
}

void sendKeyEvent(void *c, const char *character) {
    rfbClient *cl = (rfbClient *)c;

    if (!cl->maintainConnection) {
        return;
    }
    rfbKeySym sym = utf8char2rfbKeySym(c);
    //printf("sendKeyEvent converted %#06x to xkeysym: %#06x\n", (int)*character, sym);
    sendKeyEventWithKeySym(cl, sym);
}

bool sendKeyEventInt(void *c, int character) {
    rfbClient *cl = (rfbClient *)c;
    
    if (!cl->maintainConnection) {
        return false;
    }
    rfbKeySym sym = ucs2keysym(character);
    if (sym == -1) {
        return false;
    }
    //printf("sendKeyEventInt converted %#06x to xkeysym: %#06x\n", character, sym);
    sendKeyEventWithKeySym(cl, sym);
    return true;
}

void sendKeyEventWithKeySym(void *c, int sym) {
    rfbClient *cl = (rfbClient *)c;

    if (cl == NULL || !cl->maintainConnection) {
        return;
    }
    //printf("sendKeyEventWithKeySym sending xkeysym: %#06x\n", sym);
    checkForError(cl, SendKeyEvent(cl, sym, TRUE));
    checkForError(cl, SendKeyEvent(cl, sym, FALSE));
}

void sendUniDirectionalKeyEventWithKeySym(void *c, int sym, bool down) {
    rfbClient *cl = c;
    if (cl == NULL || !cl->maintainConnection) {
        return;
    }
    //printf("sendUniDirectionalKeyEventWithKeySym sending xkeysym: %#06x\n", sym);
    checkForError(cl, SendKeyEvent(cl, sym, down));
}

void sendPointerEventToServer(void *c, float totalX, float totalY, float x, float y, bool firstDown, bool secondDown, bool thirdDown, bool scrollUp, bool scrollDown) {
    rfbClient *cl = (rfbClient *)c;
    
    if (cl == NULL || !cl->maintainConnection) {
        return;
    }
    int buttonMask = 0;
    if (firstDown) {
        buttonMask = buttonMask | rfbButton1Mask;
    }
    if (secondDown) {
        buttonMask = buttonMask | rfbButton2Mask;
    }
    if (thirdDown) {
        buttonMask = buttonMask | rfbButton3Mask;
    }
    if (scrollUp) {
        buttonMask = buttonMask | rfbButton4Mask;
    }
    if (scrollDown) {
        buttonMask = buttonMask | rfbButton5Mask;
    }
    int remoteX = fbW * x / totalX;
    int remoteY = fbH * y / totalY;
    //printf("Sending pointer event at %d, %d, with mask %d\n", remoteX, remoteY, buttonMask);
    checkForError(cl, SendPointerEvent(cl, remoteX, remoteY, buttonMask));
}

void checkForError(rfbClient *cl, rfbBool res) {
    if (cl == NULL) {
        cleanup(cl, "Unexpectedly, RFB client object is null\n");
    } else if (!res) {
        cleanup(cl, "Failed to send message to server\n");
    }
}
