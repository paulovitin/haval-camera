# Captura e diagnóstico

## Caminho confirmado

```text
Câmeras → composição AVM → Qualcomm AIS/QCarCam (input 4)
                            ↓
                 cópia privada de qcarcam_test
                            ↓ fopen interceptado
                    pipe de bytes BGR24
                            ↓ Shizuku UserService / ParcelFileDescriptor
                  CameraActivity → CameraPreview / OpenGL ES
```

O fluxo já contém a composição 360° + traseira, o desenho do veículo e as linhas de orientação. Não há API de seleção de lente implementada.

| Propriedade | Valor observado |
| --- | --- |
| Dispositivo Android | `msmnile_gvmq_for_arm64`, product/device `gwm` |
| Entrada AIS/QCarCam | ID 4 |
| Resolução | 1760 × 720 |
| Formato anunciado | `0x07080300` |
| Bytes reais | BGR24 compacto, 3 bytes/pixel |
| Tamanho do quadro | 3.801.600 bytes |
| Taxa da origem | 30 fps |
| Taxa recebida no APK | aproximadamente 15 fps, com `-dumpFrame=2` |

O firmware expõe o vídeo fora do caminho que tentamos capturar com `screencap`: a captura Android mostrava o launcher enquanto o painel físico mostrava as câmeras. Os registros apontaram composição pelo lado QNX. Essa observação não impediu o acesso aos quadros pelo cliente AIS.

Camera2, por sua vez, produziu uma imagem verde. A investigação do HAL `camera.ais.so` encontrou falha ao converter a entrada RGB3. A versão funcional usa os bytes BGR24 entregues diretamente pelo cliente Qualcomm.

## Cliente nativo e pipe

O serviço valida os caminhos canônicos da biblioteca e do XML. Copia `/vendor/bin/qcarcam_test` para `files/qcarcam`, aplica permissão `0700` e inicia somente essa cópia como processo filho. O executável em `/vendor/bin` usa um namespace de linker que recusou carregar a biblioteca do APK; a cópia privada resolveu essa restrição sem alterar o original.

O XML seleciona uma única entrada:

```xml
<qcarcam_inputs>
  <input_device>
    <properties input_id="4"/>
    <display_setting display_id="0"/>
    <output_setting nbufs="5"/>
  </input_device>
</qcarcam_inputs>
```

O serviço define `LD_PRELOAD` com o caminho validado de `libcamera_stream.so` e `LD_LIBRARY_PATH` com `/vendor/lib64:/system/lib64/vndk-28:/system/lib64/vndk-sp-28`. O comando do cliente termina com:

```sh
exec "$CLIENTE_PRIVADO" -config="$XML" -noDisplay -dumpFrame=2 -nonInteractive 3>&1 1>&2
```

As variáveis acima representam caminhos que o serviço já validou. Mantenha as redireções no mesmo `exec`: na shell desta central, separar `exec 3>&1 1>&2` em um comando anterior fez o descritor 3 chegar fechado ao processo final.

`-noDisplay` evita uma janela criada pelo cliente. `-dumpFrame=2` solicita um dump a cada dois quadros, não uma captura de apenas dois quadros. Sem nossa biblioteca, esse recurso grava arquivos grandes em `/data/vendor/camera`; não o deixe executando como uma alternativa ao pipe.

A biblioteca intercepta apenas `fopen` com o prefixo `/data/vendor/camera/frame_0_`, sufixo numérico `.raw` e um modo de escrita compatível (`w`, `wb`, `w+`, `wb+` ou `w+b`). O firmware do carro em 3 de novembro de 2026 recusou todos os dumps quando o hook aceitava somente `w+`; por isso a validação continua limitada aos modos de criação para escrita, mas aceita a variante binária usada pelo cliente. Ela devolve um `FILE*` criado a partir de uma cópia do descritor 3. O `fwrite` do cliente escreve no pipe e o `fclose` fecha apenas essa cópia. As outras aberturas seguem para o `fopen` original via `RTLD_NEXT`.

O construtor nativo verifica que o descritor 3 é um pipe e configura `PR_SET_PDEATHSIG` com `SIGTERM`. A Activity recebe o fluxo por `ParcelFileDescriptor`, usa `readFully` para formar quadros completos e entrega cada um ao renderer. Logs viajam separados, por stderr, para não deslocar a fronteira dos quadros.

O shader carrega a textura como RGB e lê `.bgr` para corrigir a ordem dos canais. O renderer guarda o quadro mais recente em um buffer fixo e preserva a proporção na tela.

## Ciclo de vida

O APK pede autorização ao Shizuku, exige UID 0 e vincula `CameraService` com `daemon(false)`. O método AIDL `destroy() = 16777114` corresponde ao código de transação esperado pelo Shizuku; ele encerra o filho e o processo do UserService. Consulte a [documentação oficial de UserService](https://github.com/RikkaApps/Shizuku-API#userservice) antes de alterar esse contrato.

`onPause` pede a parada, fecha o descritor e remove o serviço. `FLAG_KEEP_SCREEN_ON` mantém a tela ligada enquanto a Activity aparece. Após receber o primeiro quadro, o monitor verifica a cada segundo se houve um intervalo superior a 2,5 segundos e apaga a imagem nesse caso. Ele ainda não impõe um timeout para a espera pelo primeiro quadro.

O código implementa a liberação da captura, mas a sessão de hardware terminou antes de concluir a verificação de fechar/reabrir. Esse comportamento continua pendente de validação.

## Verificação no carro

Faça a verificação com o veículo parado em P. Liste os dispositivos com `adb devices -l` e selecione explicitamente a central; um emulador conectado não serve para validar a origem de vídeo.

Depois de instalar e autorizar o app, verifique a imagem física e os controles **Câmera ao vivo**, **Conectar** e **Fechar**. Uma captura Android ajuda a registrar o resultado:

```sh
adb -s "$SERIAL" exec-out screencap -p > /tmp/haval-camera.png
adb -s "$SERIAL" logcat -d -s HavalCamera HavalCameraNative
```

Nos logs, procure `Success - First Frame [0:4]` e contadores `Live frames=` crescentes. A central testada filtrava mensagens INFO; a ausência desse contador sozinha não prova que o vídeo parou. Confirme atualizações reais da cena e cores corretas na tela.

Em uma shell root já autorizada, confira a pasta `/data/vendor/camera` e o processo filho `qcarcam`. Registre o estado antes e depois, preservando arquivos e processos que não sejam deste teste. Ao fechar ou pausar o app, o filho deve terminar; o serviço OEM `vendor.qti.automotive.qcarcam@1.0-service` deve continuar ativo. Reabra o app e confirme novos quadros.

Não use apenas `sys.avm.preview_status` para decidir se o overlay original está cobrindo o APK. Esse valor mudou durante a sessão; a confirmação veio da imagem do app e dos controles visíveis na tela física.

## Falhas já identificadas

| Sintoma | Conferir |
| --- | --- |
| Tela verde com Camera2 | Conversão do HAL para RGB3; preserve a rota AIS e BGR24 deste exemplo. |
| `Caminhos de captura inválidos` | `applicationId`, caminhos canônicos e as duas validações em `CameraService`. |
| Biblioteca inacessível no namespace | Executar a cópia privada, mantendo o cliente original intacto. |
| `fd 3 is not a pipe` | Redireções no mesmo `exec`, sem stdout de logs no pipe de vídeo. |
| Imagem com vermelho/azul invertidos | Leitura BGR24 e troca `.bgr` no shader. |
| `Sem imagem` ou fim do stream | Logs do processo filho, entrada 4, bibliotecas do firmware e Shizuku root. |
