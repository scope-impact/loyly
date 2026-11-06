mkdir -p /sys/kernel/tracing /sys/fs/bpf

if ! grep -q " /sys/kernel/tracing tracefs " /proc/mounts; then
  mount -t tracefs tracefs /sys/kernel/tracing || log "[init] mount tracefs failed"
fi

if ! grep -q " /sys/fs/bpf bpf " /proc/mounts; then
  mount -t bpf bpf /sys/fs/bpf || log "[init] mount bpf failed"
fi
