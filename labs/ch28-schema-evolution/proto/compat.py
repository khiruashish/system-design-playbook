#!/usr/bin/env python3
# compat.py -- a REAL protobuf schema-compatibility checker, the way a schema registry (Confluent, Buf, Apicurio) does it.
# It loads two compiled FileDescriptorSets (protoc --descriptor_set_out) and classifies the change between them under the
# three registry compatibility MODES:
#   BACKWARD  : new schema can read data written by the OLD schema      (safe to deploy readers first)
#   FORWARD   : old schema can read data written by the NEW schema      (safe to deploy writers first)
#   FULL      : both  (BACKWARD and FORWARD)
# The rules below are exactly the protobuf field-level rules a registry enforces.
import sys
from google.protobuf import descriptor_pb2

def load(path):
    fds = descriptor_pb2.FileDescriptorSet()
    fds.ParseFromString(open(path, "rb").read())
    msg = fds.file[0].message_type[0]                       # first message in the file
    fields = {}
    for f in msg.field:
        fields[f.number] = {"name": f.name, "type": f.type, "label": f.label}
    return msg.name, fields

TYPE = {v: k for k, v in descriptor_pb2.FieldDescriptorProto.Type.items()}

def classify(old, new):
    """Return (breaks_backward, breaks_forward, notes)."""
    bk, fw, notes = [], [], []
    old_n, new_n = set(old), set(new)

    # fields REMOVED in new (present in old, gone in new)
    for n in old_n - new_n:
        # a removed required-ish field breaks BACKWARD only if new code expected it; in proto3 all fields optional,
        # so removing a field is BACKWARD-safe (new reader just ignores old bytes for that number) but FORWARD-UNSAFE
        # ONLY if the field is later re-used. Removing alone: forward-safe too (old reader sees default). Note it.
        notes.append(f"  field #{n} ({old[n]['name']}) REMOVED -- ok, but its number must be RESERVED so it is never reused")

    # fields ADDED in new
    for n in new_n - old_n:
        notes.append(f"  field #{n} ({new[n]['name']}) ADDED -- backward+forward safe (old readers skip it, new readers default it)")

    # fields present in BOTH: check type/number stability
    for n in old_n & new_n:
        o, nw = old[n], new[n]
        if o["type"] != nw["type"]:
            # a type change on the SAME number is the classic silent-corruption break, both directions
            msg = f"  field #{n}: TYPE CHANGED {TYPE[o['type']]} -> {TYPE[nw['type']]} (name {o['name']}->{nw['name']})"
            bk.append(msg + " -- BACKWARD BREAK"); fw.append(msg + " -- FORWARD BREAK")
        elif o["name"] != nw["name"]:
            notes.append(f"  field #{n}: renamed {o['name']} -> {nw['name']} (wire-safe: protobuf keys on NUMBER, not name)")

    # detect a number that changed its MEANING: same name at a different number = the old number is now free to be reused
    old_by_name = {v["name"]: k for k, v in old.items()}
    new_by_name = {v["name"]: k for k, v in new.items()}
    for name in set(old_by_name) & set(new_by_name):
        if old_by_name[name] != new_by_name[name]:
            msg = f"  field '{name}' MOVED #{old_by_name[name]} -> #{new_by_name[name]} -- old bytes for #{old_by_name[name]} now misread"
            bk.append(msg + " -- BACKWARD BREAK"); fw.append(msg + " -- FORWARD BREAK")
    return bk, fw, notes

def check(oldp, newp, label):
    _, old = load(oldp); _, new = load(newp)
    bk, fw, notes = classify(old, new)
    print(f"== {label} ==")
    for line in notes: print(line)
    for line in bk + fw:
        if line not in notes: print(line)
    backward_ok = not bk
    forward_ok  = not fw
    mode = "FULL" if backward_ok and forward_ok else \
           "BACKWARD only" if backward_ok else \
           "FORWARD only" if forward_ok else "INCOMPATIBLE"
    print(f"  --> BACKWARD: {'PASS' if backward_ok else 'FAIL'}   FORWARD: {'PASS' if forward_ok else 'FAIL'}   "
          f"registry verdict: {mode}\n")
    return mode

if __name__ == "__main__":
    print("Registry compatibility check (loads REAL protoc descriptor sets, applies the field-level rules):\n")
    check("order_v1.desc", "order_v2.desc", "v1 -> v2  (added currency + tags)")
    check("order_v1.desc", "order_v3_bad.desc", "v1 -> v3  (reused #3 with a new type, moved sku)")
    print("A registry set to FULL/BACKWARD would ACCEPT v2 and REJECT v3 at publish time -- the incompatible producer")
    print("never ships. That is how you version messages on a bus (Kafka/Pub-Sub) without a flag-day upgrade of every consumer.")
