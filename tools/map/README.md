# Natural Earth country layer for AtomSpectra map

Source: Natural Earth 1:50m Admin 0 Countries, public domain.
Upstream GeoJSON used here: natural-earth-vector v5.1.1
`ne_50m_admin_0_countries.geojson`

## Regenerate `app/src/main/assets/map/countries.geojson`

1. Download the source GeoJSON into this directory as
   `ne_50m_admin_0_countries.source.geojson`.
2. From the repository root, run PowerShell:

```powershell
$src = "tools/map/ne_50m_admin_0_countries.source.geojson"
$dst = "app/src/main/assets/map/countries.geojson"
$data = Get-Content -Raw $src | ConvertFrom-Json
$features = New-Object System.Collections.Generic.List[object]
foreach ($f in $data.features) {
  $p = $f.properties
  $props = [ordered]@{
    code = [string]($(if ($p.ADM0_A3) { $p.ADM0_A3 } else { $p.ISO_A3 }))
    name = [string]($(if ($p.NAME) { $p.NAME } else { $p.ADMIN }))
  }
  if ($null -ne $p.LABEL_X -and $null -ne $p.LABEL_Y) {
    $props.label = @([double]$p.LABEL_X, [double]$p.LABEL_Y)
  }
  $features.Add([ordered]@{
    type = "Feature"
    properties = $props
    geometry = $f.geometry
  }) | Out-Null
}
$json = ([ordered]@{ type = "FeatureCollection"; features = $features } |
  ConvertTo-Json -Depth 100 -Compress)
[System.IO.File]::WriteAllText((Resolve-Path .).Path + "/$dst", $json)
```

Kept properties: `code`, `name`, optional `label` `[lon, lat]`, plus original
polygon geometry. Attribution on the map: “Made with Natural Earth”.
