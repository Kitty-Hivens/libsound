/*
 * Layout oracle for the libdbus subset libsound binds.
 *
 * Not part of the build. Run it against the headers of the libdbus the target
 * systems carry, and transcribe its output into the Kotlin ABI table.
 *
 *   gcc -o dbus-oracle dbus-oracle.c $(pkg-config --cflags --libs dbus-1)
 *   ./dbus-oracle
 *
 * This one exists because of a specific, expensive mistake. libtray and
 * libnotify both allocated 64 bytes for a DBusMessageIter, which is 72 on
 * x86_64, so libdbus wrote its trailing pointer past the allocation on every
 * iterator call -- silently, through two shipped releases each. The fix bumped
 * the reservation to 80, which is a better guess and still a guess. A number
 * the header prints is not a guess.
 *
 * DBusMessageIter is deliberately opaque in the public ABI: the struct is
 * declared with padding fields and callers are told only that it is "small".
 * That is exactly the case where reading the size from the compiler beats
 * reading it from documentation, because there is no documentation to read.
 */

#include <dbus/dbus.h>
#include <stddef.h>
#include <stdio.h>

#define SECTION(name) printf("\n== %s ==\n", name)

#define P(expr) printf("  %-46s = %lld\n", #expr, (long long)(expr))

#define CHR(expr) \
    printf("  %-46s = %-4d '%c'\n", #expr, (int)(expr), (char)(expr))

int main(void) {
    printf("libdbus headers: %d.%d.%d\n",
           DBUS_MAJOR_VERSION, DBUS_MINOR_VERSION, DBUS_MICRO_VERSION);
    printf("pointer size = %lld\n", (long long)sizeof(void *));

    SECTION("the two structs a caller has to allocate");
    /* Both are stack-allocated by the caller and opaque by design, which is why
     * their sizes are the only thing here that cannot be looked up. */
    P(sizeof(DBusMessageIter));
    P(_Alignof(DBusMessageIter));
    P(sizeof(DBusError));
    P(_Alignof(DBusError));

    SECTION("DBusError, for the fields the failure paths touch");
    P(offsetof(DBusError, name));
    P(offsetof(DBusError, message));

    SECTION("bus types");
    P(DBUS_BUS_SESSION);
    P(DBUS_BUS_SYSTEM);
    P(DBUS_BUS_STARTER);

    SECTION("message types");
    P(DBUS_MESSAGE_TYPE_INVALID);
    P(DBUS_MESSAGE_TYPE_METHOD_CALL);
    P(DBUS_MESSAGE_TYPE_METHOD_RETURN);
    P(DBUS_MESSAGE_TYPE_ERROR);
    P(DBUS_MESSAGE_TYPE_SIGNAL);

    SECTION("type codes -- single-byte ASCII on the wire");
    CHR(DBUS_TYPE_INVALID);
    CHR(DBUS_TYPE_BYTE);
    CHR(DBUS_TYPE_BOOLEAN);
    CHR(DBUS_TYPE_INT16);
    CHR(DBUS_TYPE_UINT16);
    CHR(DBUS_TYPE_INT32);
    CHR(DBUS_TYPE_UINT32);
    CHR(DBUS_TYPE_INT64);
    CHR(DBUS_TYPE_UINT64);
    CHR(DBUS_TYPE_DOUBLE);
    CHR(DBUS_TYPE_STRING);
    CHR(DBUS_TYPE_OBJECT_PATH);
    CHR(DBUS_TYPE_SIGNATURE);
    CHR(DBUS_TYPE_ARRAY);
    CHR(DBUS_TYPE_VARIANT);
    CHR(DBUS_TYPE_STRUCT);
    CHR(DBUS_TYPE_DICT_ENTRY);

    SECTION("name request flags and replies");
    P(DBUS_NAME_FLAG_ALLOW_REPLACEMENT);
    P(DBUS_NAME_FLAG_REPLACE_EXISTING);
    P(DBUS_NAME_FLAG_DO_NOT_QUEUE);
    P(DBUS_REQUEST_NAME_REPLY_PRIMARY_OWNER);
    P(DBUS_REQUEST_NAME_REPLY_IN_QUEUE);
    P(DBUS_REQUEST_NAME_REPLY_EXISTS);
    P(DBUS_REQUEST_NAME_REPLY_ALREADY_OWNER);

    SECTION("dispatch");
    P(DBUS_DISPATCH_DATA_REMAINS);
    P(DBUS_DISPATCH_COMPLETE);
    P(DBUS_DISPATCH_NEED_MEMORY);

    SECTION("handler results");
    P(DBUS_HANDLER_RESULT_HANDLED);
    P(DBUS_HANDLER_RESULT_NOT_YET_HANDLED);
    P(DBUS_HANDLER_RESULT_NEED_MEMORY);

    SECTION("timeouts");
    P(DBUS_TIMEOUT_USE_DEFAULT);
    P(DBUS_TIMEOUT_INFINITE);

    return 0;
}
