import { readFileSync } from 'node:fs'
import { fromBinary } from '@bufbuild/protobuf'
import { IndexSchema, SymbolRole } from '@scip-code/scip'

const index = fromBinary(IndexSchema, readFileSync(process.argv[2]))
for (const document of index.documents) {
  for (const item of document.occurrences) {
    if (!item.symbol || item.range.length < 3) continue
    const [row, column] = item.range
    console.log(JSON.stringify({
      symbol: item.symbol,
      path: document.relativePath,
      line: row + 1,
      column,
      def: (item.symbolRoles & SymbolRole.Definition) !== 0,
    }))
  }
}
