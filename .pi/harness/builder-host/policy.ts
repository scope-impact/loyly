// What the builder's Gondolin VM allows, beyond what the guest image itself carries. Gondolin's own
// JSON (its build config, recorded in the image's manifest.json) has no runtime keys for these, so
// they are Gondolin API options kept here, one place that both vm-daemon.ts enforces and host.ts
// states in the builder's prompt.

/** The builder room's mount point in the VM, read-write; no other host directory is mounted. */
export const GUEST_WORKSPACE = "/workspace";

/**
 * Hosts the VM may reach over HTTP(S), by exact name, as Gondolin's `createHttpHooks({ allowedHosts })`
 * takes them: the package registries the builder stacks need, and nothing else. Any other request is
 * refused before its name is resolved on the host. Empty: no egress at all. Gondolin routes no other
 * protocol out of the VM (no SSH or TCP mapping is configured), and its DNS is synthetic.
 */
export const ALLOWED_HOSTS: readonly string[] = [
	"registry.npmjs.org", // Node / npm
	"repo1.maven.org", // Clojure / Maven
	"repo.clojars.org",
	"github.com", // git deps by coordinate (ORC, Grain)
	"codeload.github.com",
	"objects.githubusercontent.com",
	"dl-cdn.alpinelinux.org", // apk, the package manager of both builder images (Alpine)
];

/** Where a builder records what it had to install, for the image's Dockerfile; outside APP_DIR. */
export const IMAGE_ADDITIONS = `${GUEST_WORKSPACE}/.builder/image-additions.txt`;

/** The user the builder runs as in the VM; vm-daemon.ts refuses to start otherwise. */
export const GUEST_USER = "root";
