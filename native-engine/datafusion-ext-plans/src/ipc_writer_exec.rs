// Copyright 2022 The Blaze Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

use std::{any::Any, fmt::Formatter, io::Cursor, sync::Arc};

use arrow::datatypes::SchemaRef;
use async_trait::async_trait;
use blaze_jni_bridge::{
    jni_call, jni_call_static, jni_new_direct_byte_buffer, jni_new_global_ref, jni_new_string,
};
use datafusion::{
    error::{DataFusionError, Result},
    execution::context::TaskContext,
    physical_expr::PhysicalSortExpr,
    physical_plan::{
        metrics::{BaselineMetrics, ExecutionPlanMetricsSet, MetricsSet},
        stream::RecordBatchStreamAdapter,
        DisplayAs, DisplayFormatType, ExecutionPlan, Partitioning, SendableRecordBatchStream,
        Statistics,
    },
};
use datafusion_ext_commons::{io::write_one_batch, streams::coalesce_stream::CoalesceInput};
use futures::{stream::once, StreamExt, TryStreamExt};
use jni::objects::{GlobalRef, JObject};

use crate::common::output::TaskOutputter;

#[derive(Debug)]
pub struct IpcWriterExec {
    input: Arc<dyn ExecutionPlan>,
    ipc_consumer_resource_id: String,
    metrics: ExecutionPlanMetricsSet,
}

impl IpcWriterExec {
    pub fn new(input: Arc<dyn ExecutionPlan>, ipc_consumer_resource_id: String) -> Self {
        Self {
            input,
            ipc_consumer_resource_id,
            metrics: ExecutionPlanMetricsSet::new(),
        }
    }
}

impl DisplayAs for IpcWriterExec {
    fn fmt_as(&self, _t: DisplayFormatType, f: &mut Formatter) -> std::fmt::Result {
        write!(f, "IpcWriter")
    }
}

#[async_trait]
impl ExecutionPlan for IpcWriterExec {
    fn as_any(&self) -> &dyn Any {
        self
    }

    fn schema(&self) -> SchemaRef {
        self.input.schema()
    }

    fn output_partitioning(&self) -> Partitioning {
        self.input.output_partitioning()
    }

    fn output_ordering(&self) -> Option<&[PhysicalSortExpr]> {
        self.input.output_ordering()
    }

    fn children(&self) -> Vec<Arc<dyn ExecutionPlan>> {
        vec![self.input.clone()]
    }

    fn with_new_children(
        self: Arc<Self>,
        children: Vec<Arc<dyn ExecutionPlan>>,
    ) -> Result<Arc<dyn ExecutionPlan>> {
        if children.len() != 1 {
            return Err(DataFusionError::Plan(
                "IpcWriterExec expects one children".to_string(),
            ));
        }
        Ok(Arc::new(IpcWriterExec::new(
            self.input.clone(),
            self.ipc_consumer_resource_id.clone(),
        )))
    }

    fn execute(
        &self,
        partition: usize,
        context: Arc<TaskContext>,
    ) -> Result<SendableRecordBatchStream> {
        let baseline_metrics = BaselineMetrics::new(&self.metrics, partition);
        let ipc_consumer_local = jni_call_static!(
            JniBridge.getResource(
                jni_new_string!(&self.ipc_consumer_resource_id)?.as_obj()) -> JObject
        )?;
        let ipc_consumer = jni_new_global_ref!(ipc_consumer_local.as_obj())?;
        let input = self.input.execute(partition, context.clone())?;
        let input_coalesced = context.coalesce_with_default_batch_size(input, &baseline_metrics)?;

        Ok(Box::pin(RecordBatchStreamAdapter::new(
            self.schema(),
            once(write_ipc(
                input_coalesced,
                context,
                ipc_consumer,
                baseline_metrics,
            ))
            .try_flatten(),
        )))
    }

    fn metrics(&self) -> Option<MetricsSet> {
        Some(self.metrics.clone_inner())
    }

    fn statistics(&self) -> Statistics {
        todo!()
    }
}

pub async fn write_ipc(
    mut input: SendableRecordBatchStream,
    context: Arc<TaskContext>,
    ipc_consumer: GlobalRef,
    metrics: BaselineMetrics,
) -> Result<SendableRecordBatchStream> {
    let schema = input.schema();
    context.output_with_sender("IpcWrite", schema.clone(), move |_sender| async move {
        while let Some(batch) = input.next().await.transpose()? {
            let timer = metrics.elapsed_compute().timer();
            let num_rows = batch.num_rows();

            let mut buffer = vec![];
            write_one_batch(&batch, &mut Cursor::new(&mut buffer), true, None)?;
            drop(timer);
            metrics.record_output(num_rows);

            let buf = jni_new_direct_byte_buffer!(&buffer)?;
            let _consumed = jni_call!(
                ScalaFunction1(ipc_consumer.as_obj()).apply(buf.as_obj()) -> JObject
            )?;
        }
        Ok(())
    })
}
