# Registro de validação

## Sessão de 2 de novembro de 2026

Testamos uma central de Haval H6 identificada pelo Android como `msmnile_gvmq_for_arm64`, product/device `gwm`, com o carro parado em P. Não registramos uma identificação de firmware suficiente para anunciar compatibilidade com todas as centrais desse modelo.

| Verificação | Resultado |
| --- | --- |
| Enumerar origem Qualcomm | Entrada ID 4, 1760 × 720, formato anunciado `0x07080300`, 30 fps. |
| Capturar imagem real | Quadro BGR24 com visão 360° e traseira, correspondente à câmera física. |
| Captura curta com interface AVM fechada | Recebemos um quadro real; isso não valida todos os estados futuros da central. |
| Mostrar dentro do APK | Imagem com cores corretas em `CameraPreview`; usuário confirmou os controles do app na tela física. |
| Atualização contínua observada | Logs avançaram de 1 para 150, 300 e 450 quadros, cerca de 15 fps ao longo de 30 segundos. Não representa teste prolongado. |
| Gravação de frames no armazenamento | `/data/vendor/camera` permaneceu vazio durante a verificação do APK. |
| Fechar/pausar e conferir processos | Implementado; verificação final ficou pendente quando ADB/Telnet perderam conexão. |
| Reabrir e reconectar | Pendente de validação completa. |
| Reiniciar a central | Não testado. |
| Outro app usando a câmera simultaneamente | Não testado. |
| Outras centrais/firmwares ou veículo em movimento | Não testado. |

A primeira imagem real preservada nesta investigação tem horário 14h13. O projeto do APK começou às 14h17 e o primeiro quadro do app funcional apareceu às 14h37. Esses horários vêm dos arquivos e logs locais, não de uma medição completa do tempo de conversa.

## Publicação do exemplo

O código Java, AIDL, C e o manifesto desta versão vêm do protótipo 0.2 que mostrou vídeo no carro. A preparação do repositório acrescenta documentação, build para macOS/Linux, verificação de dependências, CMake para integração e avisos de licença no APK. O workflow verifica compilação e empacotamento; ele não substitui a validação física acima.

O build local no macOS passou com JDK 17 e os componentes de SDK/NDK fixados no script. Também passaram a compilação pelo CMake, `apksigner verify`, `zipalign`, a integridade do ZIP e `actionlint`. A comparação com o APK testado confirmou bytes idênticos nos seguintes arquivos executáveis e no manifesto:

| Arquivo dentro do APK | SHA-256 |
| --- | --- |
| `classes.dex` | `d409752efe1549d4f7da0341aca5b8813107dacef2bb556047bae6abd1ecf9cc` |
| `lib/arm64-v8a/libcamera_stream.so` | `be064b23418d3e9cc3f88eb4fe95e43beade9acecf4387ab7c8f3dab5c26f87d` |
| `AndroidManifest.xml` | `9874585469131f8e654d39520916eb333e8412eb8eab824234b3c286613f5b36` |

Ao gerar um release, registre o SHA-256 do APK nos assets. Builds podem produzir assinaturas diferentes conforme o keystore usado. Compare também `classes.dex`, a biblioteca nativa e o manifesto quando afirmar que um novo empacotamento preserva o código testado.

As fotografias da garagem, quadros raw, logs completos e cópias de binários OEM usados na investigação não fazem parte do repositório público.

## Acrescentar evidência

Ao testar outra central, registre modelo, versão Android, identificação pública do firmware, versão do Shizuku e modo de execução, commit/versão do APK, duração observada e resultado de cada verificação. Remova VIN, endereços de rede, credenciais e imagens pessoais antes de compartilhar diagnósticos. Marque como pendente qualquer cenário que não tenha sido executado.
