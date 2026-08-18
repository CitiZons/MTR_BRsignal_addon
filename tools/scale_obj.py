"""Scale Blockbench OBJ vertex positions (0..16 pixel units) to Forge block units (0..1) by dividing by 16."""
import re
import sys


def main():
    path = sys.argv[1]
    vertex_re = re.compile(r"^(v)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)(.*)$")
    out = []
    count = 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            match = vertex_re.match(line)
            if match:
                x, y, z = (float(match.group(i)) / 16.0 for i in (2, 3, 4))
                out.append("v %.6f %.6f %.6f%s\n" % (x, y, z, match.group(5)))
                count += 1
            else:
                out.append(line)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(out)
    print("scaled %d vertices" % count)


if __name__ == "__main__":
    main()
