{
  description = "libsound: a development shell carrying the system libraries it opens at runtime";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      # Linux only, and deliberately. What this shell is for is the sound server
      # and the session bus; neither exists on darwin, so a shell there would be
      # a JDK and a promise. macOS is covered by its own CI row against a real
      # output unit.
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forEach = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forEach (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            # jdk25 rather than the unversioned jdk, which is 21 and below this
            # project's floor of 22. Naming a version is what makes this file
            # rot -- jdk23 was dropped from nixpkgs as end of life -- which is
            # why CI runs the shell rather than trusting it.
            jdk25

            pulseaudio # the daemon the suites talk to, and pactl with it
            dbus # dbus-run-session, for the suites that publish MPRIS
            glib # gdbus, which those suites make their assertions through
            playerctl # and the reader that proves MPRIS rather than just D-Bus
            git # the version string is a git describe
          ];

          # The reason this file exists at all.
          #
          # Nothing native ships here: every system library is opened by soname
          # at runtime. On a filesystem whose only copy lives in the store, a
          # bare dlopen finds nothing -- it searches LD_LIBRARY_PATH, then a
          # cache and a /usr/lib that are not there. This is the wrapProgram
          # line from the README in the form a shell can use, and without it the
          # backend falls back and says why.
          LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath [ pkgs.libpulseaudio pkgs.dbus ];

          JAVA_HOME = pkgs.jdk25.home;
        };
      });
    };
}
