// rayneo_ctl.c — direct FFalcon HID command sender for RayNeo glasses.
// Wire protocol reversed from libFFalconXRServer.so (RayNeoXR V2.1.1):
//   OUT endpoint 0x01 (interrupt, 64B), report: [0]=0x66 [1]=cmd [2]=subcmd [3..]payload
//   Known commands: 0x06=SwitchTo3D 0x07=SwitchTo2D
// Usage: rayneo_ctl <busnum> <devnum> <cmd> [subcmd] [payload hex bytes...]
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <bus> <dev> <cmd> [subcmd] [hex...]\n", argv[0]);
        return 2;
    }
    int bus = atoi(argv[1]), dev = atoi(argv[2]);
    int cmd = (int)strtol(argv[3], NULL, 0);
    int sub = argc > 4 ? (int)strtol(argv[4], NULL, 0) : 0;

    char path[64];
    snprintf(path, sizeof(path), "/dev/bus/usb/%03d/%03d", bus, dev);
    int fd = open(path, O_RDWR);
    if (fd < 0) { fprintf(stderr, "open %s: %s\n", path, strerror(errno)); return 1; }

    // Detach kernel HID driver if it claimed interface 0.
    struct usbdevfs_getdriver gd = { .interface = 0 };
    if (ioctl(fd, USBDEVFS_GETDRIVER, &gd) == 0) {
        printf("kernel driver '%s' attached, detaching\n", gd.driver);
        struct usbdevfs_ioctl d = { .ifno = 0, .ioctl_code = USBDEVFS_DISCONNECT, .data = NULL };
        if (ioctl(fd, USBDEVFS_IOCTL, &d) != 0)
            fprintf(stderr, "detach: %s (continuing)\n", strerror(errno));
    }
    if (ioctl(fd, USBDEVFS_CLAIMINTERFACE, (int[]){0}) != 0) {
        fprintf(stderr, "claim: %s\n", strerror(errno));
        close(fd); return 1;
    }

    unsigned char buf[64];
    memset(buf, 0, sizeof(buf));
    buf[0] = 0x66;
    buf[1] = (unsigned char)cmd;
    buf[2] = (unsigned char)sub;
    int extra = 0;
    for (int i = 5; i < argc && extra < 54; i++, extra++)
        buf[3 + extra] = (unsigned char)strtol(argv[i], NULL, 16);

    struct usbdevfs_bulktransfer bt = {
        .ep = 0x01, .len = sizeof(buf), .timeout = 300, .data = buf,
    };
    int n = ioctl(fd, USBDEVFS_BULK, &bt);
    if (n < 0) { fprintf(stderr, "bulk out: %s\n", strerror(errno)); }
    else printf("sent %d bytes: 66 %02x %02x ...\n", n, cmd, sub);

    // Read response from 0x81 (status report).
    unsigned char in[64];
    memset(in, 0, sizeof(in));
    struct usbdevfs_bulktransfer br = {
        .ep = 0x81, .len = sizeof(in), .timeout = 500, .data = in,
    };
    n = ioctl(fd, USBDEVFS_BULK, &br);
    if (n > 0) {
        printf("response %d bytes:", n);
        for (int i = 0; i < n && i < 32; i++) printf(" %02x", in[i]);
        printf("\n");
    } else {
        printf("no response within 500ms (%s) — often normal for state commands\n", strerror(errno));
    }

    ioctl(fd, USBDEVFS_RELEASEINTERFACE, (int[]){0});
    close(fd);
    return 0;
}
