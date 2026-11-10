#!/bin/bash

# Default parameters

EXP_NAME=7b_v2_sft_v4_0905

OUTPUT_DIR="/nfsEDS/youjinliang/low-code/models/$EXP_NAME"
MODEL_NAME="/models/models--aiXcoder--aixcoder-7b-v2/94a44b5caba59512e26da3335e1b8bdd07ffc5fa/"
DATASET_NAME=/nfsEDS/youjinliang/low-code/data/v4/train.jsonl
EVAL_DATASET_NAME=/nfsEDS/youjinliang/low-code/data/v4/test.jsonl


CONFIG_FILE="examples/accelerate_configs/deepspeed_zero3.yaml"
MAX_STEPS=-1
BATCH_SIZE=1
LEARNING_RATE=5.0e-6
NUM_EPOCHS=10
SEQ_LEN=32768

# Single-node 8-GPU training settings
NUM_GPUS=8
IMAGE_NAME="trl_env:0910"
WORKSPACE_PATH="/nfsEDS/youjinliang/low-code/"
WORK_DIR="/nfsEDS/youjinliang/low-code/aixcoder-7b-v2"
CONTAINER_NAME="yjl-trl-sft"
WANDB_API_KEY=""


# Define experiment name and log file

LOG_FILE="$WORK_DIR/logs/$EXP_NAME.log"

rm -f core.*
set -x

# Environment variables for NCCL and other settings
ENV_ARGS=""

# Command for running accelerate on a single 8-GPU machine
CMD="""accelerate launch --config_file $CONFIG_FILE \
    --num_processes $NUM_GPUS --num_machines 1 --mixed_precision bf16 \
    examples/scripts/sft-1206-multi-granularity/sft_aix_base_spm_comment_validation.py \
    --model_name $MODEL_NAME \
    --dataset_name $DATASET_NAME \
    --eval_dataset_name $EVAL_DATASET_NAME \
    --output_dir $OUTPUT_DIR \
    --max_steps $MAX_STEPS \
    --per_device_train_batch_size $BATCH_SIZE \
    --gradient_accumulation_steps 1 \
    --max_seq_length $SEQ_LEN \
    --num_train_epochs $NUM_EPOCHS \
    --attn_implementation flash_attention_2 \
    --logging_steps 1 \
    --save_only_model \
    --save_strategy epoch \
    --evaluation_strategy epoch \
    --per_device_eval_batch_size 1 \
    --learning_rate $LEARNING_RATE \
    --warmup_ratio 0.05 \
    --use_liger_kernel \
    --gradient_checkpointing \
    --bf16 \
    --bf16_full_eval \
    --weight_decay 0.01 \
    --max_grad_norm 1.0 \
    --report_to tensorboard
"""

# Combining environment variables with the command
RUN_CMD="$ENV_ARGS TOKENIZERS_PARALLELISM=true $CMD"



echo "Starting single-node 8-GPU training..."

# Run directly on the local machine via Docker
docker kill $CONTAINER_NAME || true
docker rm $CONTAINER_NAME || true
sleep 4
docker run -d -it --gpus \"device=0,1,2,3,4,5,6,7\" --net=host --name $CONTAINER_NAME \
    -v /dev/shm:/dev/shm \
    -v $WORKSPACE_PATH:$WORKSPACE_PATH \
    -v $WORK_DIR:$WORK_DIR \
    -v $DATASET_NAME:$DATASET_NAME \
    -v $EVAL_DATASET_NAME:$EVAL_DATASET_NAME \
    -v $MODEL_NAME:$MODEL_NAME \
    -w $WORK_DIR $IMAGE_NAME /bin/bash -c "$RUN_CMD"
docker logs -f $CONTAINER_NAME >& $LOG_FILE &