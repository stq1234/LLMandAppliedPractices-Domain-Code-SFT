# Copyright 2023 The HuggingFace Inc. team. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""
# Full training
python examples/scripts/sft.py \
    --model_name_or_path Qwen/Qwen2-0.5B \
    --dataset_name trl-lib/Capybara \
    --learning_rate 2.0e-5 \
    --num_train_epochs 1 \
    --packing \
    --per_device_train_batch_size 2 \
    --gradient_accumulation_steps 8 \
    --gradient_checkpointing \
    --logging_steps 25 \
    --eval_strategy steps \
    --eval_steps 100 \
    --output_dir Qwen2-0.5B-SFT \
    --push_to_hub

# LoRA
python examples/scripts/sft.py \
    --model_name_or_path Qwen/Qwen2-0.5B \
    --dataset_name trl-lib/Capybara \
    --learning_rate 2.0e-4 \
    --num_train_epochs 1 \
    --packing \
    --per_device_train_batch_size 2 \
    --gradient_accumulation_steps 8 \
    --gradient_checkpointing \
    --logging_steps 25 \
    --eval_strategy steps \
    --eval_steps 100 \
    --use_peft \
    --lora_r 32 \
    --lora_alpha 16 \
    --output_dir Qwen2-0.5B-SFT \
    --push_to_hub
"""



import sys, os
import torch
sys.path.insert(0, os.getcwd())
from datasets import load_dataset,  load_from_disk
from transformers import AutoTokenizer
from datasets import Features, Value
from dataclasses import dataclass, field
from typing import Optional
import os
from trl import (
    ModelConfig,
    ScriptArguments,
    SFTConfig,
    SFTTrainer,
    TrlParser,
    get_kbit_device_map,
    get_peft_config,
    get_quantization_config,
    DataCollatorForCompletionOnlyLM,
)
import torch

@dataclass
class EvalDataArguments:
    eval_dataset_name: Optional[str] = field(
        default=None,
        metadata={"help": "Path to the evaluation dataset (jsonl). If not set, 5% of the training data is held out for eval."},
    )


if __name__ == "__main__":
    parser = TrlParser((ScriptArguments, SFTConfig, ModelConfig, EvalDataArguments))
    script_args, training_args, model_config, eval_args = parser.parse_args_and_config()

    ################
    # Model init kwargs & Tokenizer
    ################
    quantization_config = get_quantization_config(model_config)
    model_kwargs = dict(
        revision=model_config.model_revision,
        trust_remote_code=model_config.trust_remote_code,
        attn_implementation=model_config.attn_implementation,
        torch_dtype=torch.bfloat16,
        use_cache=False if training_args.gradient_checkpointing else True,
        device_map=get_kbit_device_map() if quantization_config is not None else None,
        quantization_config=quantization_config,
    )
    training_args.model_init_kwargs = model_kwargs
    # tokenizer.pad_token = tokenizer.eos_token
    tokenizer = AutoTokenizer.from_pretrained(
        model_config.model_name_or_path, trust_remote_code=model_config.trust_remote_code, use_fast=True
    )
    tokenizer.padding_side = "right"


    dataset = load_dataset("json", data_files=script_args.dataset_name, split='train')
    shuffled_dataset = dataset.shuffle(seed=42)

    if eval_args.eval_dataset_name is not None:
        # Use the dedicated evaluation set; all training data is used for training
        train_dataset = shuffled_dataset
        test_dataset = load_dataset("json", data_files=eval_args.eval_dataset_name, split='train')
    else:
        train_test_split = shuffled_dataset.train_test_split(test_size=0.05)
        train_dataset = train_test_split['train']
        test_dataset = train_test_split['test']

    # train_test_split = dataset.train_test_split(test_size=0.05)
    # train_dataset = train_test_split['train']
    # test_dataset = train_test_split['test']
    train_dataset = train_dataset.shuffle(seed=42)
    test_dataset = test_dataset.shuffle(seed=42)


    EOT_TOKEN = "</s>"
    def formatting_prompts_func(examples):
        prompts = examples["prompt"]
        completions = examples["output"] if "output" in examples else examples['source']
        texts = []
        for prompt, completion in zip(prompts, completions):
            text = (
                prompt + completion + EOT_TOKEN
            )
            texts.append(text)
        return texts

    # instruction_template = "<s><!--the file path is: README.md-->\n"
    instruction_template = None
    response_template = "The complete source code begins below:\n"

    collator = DataCollatorForCompletionOnlyLM(instruction_template=instruction_template, response_template=response_template, tokenizer=tokenizer, mlm=False, padding_free=True)
    
    # response_template = "The completed code:\n"
    # collator = DataCollatorForCompletionOnlyLM(response_template=response_template, tokenizer=tokenizer, mlm=False, padding_free=True)
    


    ################
    # Training
    ################
    trainer = SFTTrainer(
        model=model_config.model_name_or_path,
        args=training_args,
        train_dataset=train_dataset,
        eval_dataset=test_dataset,
        processing_class=tokenizer,
        peft_config=get_peft_config(model_config),
        data_collator=collator,
        formatting_func = formatting_prompts_func,
    )


    trainer.train()

    # Save and push to hub
    trainer.save_model(training_args.output_dir)
    if training_args.push_to_hub:
        trainer.push_to_hub(dataset_name=script_args.dataset_name)



