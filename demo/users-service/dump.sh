output="${1:-all-java.txt}"; dir="${2:-.}"; cat /dev/null > "$output"; find "$dir" -path "$dir/target" -prune -o \( -name "*.java" -o -name "pom.xml" \) -print0 | while IFS= read -r -d '' file; do
  echo "===== FILE: $file =====" >> "$output"
  cat "$file" >> "$output"
  echo >> "$output"
done