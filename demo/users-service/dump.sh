cat /dev/null > all-java.txt; find . -path ./target -prune -o -name "*.java" -print0 | while IFS= read -r -d '' file; do
  echo "===== FILE: $file =====" >> all-java.txt
  cat "$file" >> all-java.txt
  echo >> all-java.txt
done